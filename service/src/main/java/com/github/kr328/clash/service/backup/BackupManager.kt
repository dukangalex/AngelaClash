package com.github.kr328.clash.service.backup

import android.content.Context
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ChainBinding
import com.github.kr328.clash.service.store.ChainStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileChanged
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Explicit, passphrase protected backup of everything a user would be sad to
 * lose when moving to a new device:
 *
 *  - profiles (database rows + config.yaml, which holds the node credentials)
 *  - clash/ working files (override script, script.js and its options)
 *  - chain bindings (entry / landing)
 *  - per group selections
 *
 * Android's automatic cloud backup stays switched off: it would upload
 * credentials without consent. This is the opt-in replacement, and it is the
 * only supported way to migrate data.
 */
class BackupManager(context: Context) {
    private val app = context.applicationContext

    data class Summary(
        val profiles: Int,
        val clashFiles: Int,
        val chainBindings: Int,
        val selections: Int,
    )

    data class RestoreResult(
        val summary: Summary,
        val createdAt: Long,
        val appVersion: String,
    )

    /** @return counts of what was written, for the confirmation toast. */
    suspend fun exportTo(
        output: OutputStream,
        passphrase: CharArray,
        appVersion: String,
    ): Summary {
        val content = collect()
        val plain = BackupCodec.encode(content, appVersion)
        BackupCrypto.encrypt(plain, passphrase, output)

        return content.summary()
    }

    suspend fun restoreFrom(input: InputStream, passphrase: CharArray): RestoreResult {
        val content = BackupCodec.decode(BackupCrypto.decrypt(input, passphrase))
        apply(content)

        return RestoreResult(
            summary = content.summary(),
            createdAt = content.createdAt,
            appVersion = content.appVersion,
        )
    }

    private fun BackupContent.summary() = Summary(
        profiles = profiles.size,
        clashFiles = clashFiles.size,
        chainBindings = chain.size,
        selections = selections.size,
    )

    private suspend fun collect(): BackupContent {
        // Working files of the running core: override script, custom script and
        // its options all live here.
        val clashFiles = LinkedHashMap<String, ByteArray>()
        val clashDir = app.filesDir.resolve("clash")
        if (clashDir.isDirectory) {
            clashDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name }
                ?.forEach { clashFiles[it.name] = it.readBytes() }
        }

        val profileDir = app.importedDir
        val importedDao = ImportedDao()
        val selectionDao = SelectionDao()

        val profiles = ArrayList<BackupProfile>()
        val selections = ArrayList<BackupSelection>()

        for (uuid in importedDao.queryAllUUIDs()) {
            val row = importedDao.queryByUUID(uuid) ?: continue
            val config = profileDir.resolve(uuid.toString()).resolve(CONFIG_NAME)
            // A profile without a config cannot be restored meaningfully; skip it
            // rather than writing a backup that fails half way on import.
            if (!config.isFile) continue

            profiles += BackupProfile(
                uuid = row.uuid,
                name = row.name,
                type = row.type,
                source = row.source,
                interval = row.interval,
                upload = row.upload,
                download = row.download,
                total = row.total,
                expire = row.expire,
                createdAt = row.createdAt,
                ageSecretKey = row.ageSecretKey,
                config = config.readBytes(),
            )
            selectionDao.querySelections(uuid).forEach {
                selections += BackupSelection(it.uuid, it.proxy, it.selected)
            }
        }

        val chain = ChainStore(app).all().mapValues { (_, binding) ->
            BackupChainBinding(binding.entry, binding.landing, binding.landingProfile)
        }

        return BackupContent(
            createdAt = System.currentTimeMillis(),
            appVersion = "",
            activeProfile = ServiceStore(app).activeProfile?.toString(),
            clashFiles = clashFiles,
            profiles = profiles,
            chain = chain,
            selections = selections,
        )
    }

    /**
     * Restore is additive: existing profiles are matched by UUID and updated in
     * place, nothing is ever deleted. Every step is idempotent, so a restore that
     * is interrupted by the system can simply be run again.
     */
    private suspend fun apply(content: BackupContent) {
        // 1. clash/ working files
        val clashDir = app.filesDir.resolve("clash").apply { mkdirs() }
        for ((name, bytes) in content.clashFiles) {
            val target = clashDir.resolve(name)
            // Guard against anything the codec let through by mistake.
            if (target.canonicalFile.parentFile != clashDir.canonicalFile) continue
            writeAtomically(target, bytes)
        }

        // 2. profiles: config.yaml first, then the database row, so the UI can
        //    never observe a row without its config behind it.
        val profileDir = app.importedDir.apply { mkdirs() }
        val importedDao = ImportedDao()
        for (profile in content.profiles) {
            val dir = profileDir.resolve(profile.uuid.toString()).apply { mkdirs() }
            writeAtomically(dir.resolve(CONFIG_NAME), profile.config)

            val row = Imported(
                uuid = profile.uuid,
                name = profile.name,
                type = profile.type,
                source = profile.source,
                interval = profile.interval,
                upload = profile.upload,
                download = profile.download,
                total = profile.total,
                expire = profile.expire,
                createdAt = profile.createdAt,
                ageSecretKey = profile.ageSecretKey,
            )
            if (importedDao.exists(profile.uuid)) {
                importedDao.update(row)
            } else {
                importedDao.insert(row)
            }
        }

        // 3. chain bindings
        val chainStore = ChainStore(app)
        content.chain.forEach { (uuid, binding) ->
            chainStore.put(
                uuid,
                ChainBinding(
                    entry = binding.entry,
                    landingProfile = binding.landingProfile,
                    landing = binding.landing,
                ),
            )
        }

        // 4. selected profile, but only when it is one we just restored
        val activeProfile = content.activeProfile?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        if (activeProfile != null && importedDao.exists(activeProfile)) {
            ServiceStore(app).activeProfile = activeProfile
        }

        // 5. group selections (foreign key: only for profiles we actually have)
        val selectionDao = SelectionDao()
        val present = importedDao.queryAllUUIDs().toHashSet()
        content.selections.forEach { selection ->
            if (present.contains(selection.uuid)) {
                selectionDao.setSelected(
                    Selection(selection.uuid, selection.proxy, selection.selected)
                )
            }
        }

        // 6. one notification at the end: the UI reloads the list, and the core
        //    only re-reads its config if the active profile is the one we touched.
        val notify = activeProfile ?: content.profiles.firstOrNull()?.uuid
        if (notify != null) {
            app.sendProfileChanged(notify)
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val dir = target.parentFile ?: return
        val temp = File.createTempFile("restore-", ".tmp", dir)
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.writeBytes(bytes)
            }
        } finally {
            temp.delete()
        }
    }

    companion object {
        const val CONFIG_NAME = "config.yaml"
        const val MIN_PASSPHRASE = 8
    }
}
