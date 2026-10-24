package com.github.kr328.clash.service.backup

import android.util.Base64
import com.github.kr328.clash.service.model.Profile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted backup container.
 *
 * Layout on disk: `A G B 0x01 | salt(16) | iv(12) | AES-256-GCM ciphertext`.
 * The passphrase never leaves the device; losing it means losing the archive.
 * Passphrase stretching uses PBKDF2-HMAC-SHA1 because it exists on API 21.
 */
internal object BackupCrypto {
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), 0x01)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 120_000
    private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024

    fun encrypt(plain: ByteArray, passphrase: CharArray, out: OutputStream) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))

        val body = cipher.doFinal(plain)
        out.write(MAGIC)
        out.write(salt)
        out.write(iv)
        out.write(body)
        out.flush()
    }

    fun decrypt(input: InputStream, passphrase: CharArray): ByteArray {
        val bytes = readBounded(input)
        val header = MAGIC.size + SALT_BYTES + IV_BYTES
        if (bytes.size < header + TAG_BITS / 8) {
            throw IllegalArgumentException("备份文件不完整")
        }
        for (index in 0 until MAGIC.size - 1) {
            if (bytes[index] != MAGIC[index]) {
                throw IllegalArgumentException("不是 Angela Clash 备份文件")
            }
        }
        if (bytes[MAGIC.size - 1] != MAGIC[MAGIC.size - 1]) {
            throw IllegalArgumentException("备份格式版本不受支持，请升级应用")
        }

        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val iv = bytes.copyOfRange(MAGIC.size + SALT_BYTES, header)
        val body = bytes.copyOfRange(header, bytes.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        return try {
            cipher.doFinal(body)
        } catch (_: GeneralSecurityException) {
            // AEADBadTagException: wrong passphrase or tampered archive.
            throw WrongPassphraseException()
        }
    }

    /**
     * Reads the archive while refusing to buffer more than [MAX_ARCHIVE_BYTES],
     * so a hostile file cannot exhaust the heap before the size check runs.
     */
    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream(64 * 1024)
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_ARCHIVE_BYTES) {
                throw IllegalArgumentException("备份文件过大")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

class WrongPassphraseException : IllegalArgumentException("口令不正确，或备份文件已损坏")

data class BackupProfile(
    val uuid: UUID,
    val name: String,
    val type: Profile.Type,
    val source: String,
    val interval: Long,
    val upload: Long,
    val download: Long,
    val total: Long,
    val expire: Long,
    val createdAt: Long,
    val ageSecretKey: String?,
    val config: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = uuid.hashCode()
}

data class BackupChainBinding(val entry: String, val landing: String, val landingProfile: String)

data class BackupSelection(val uuid: UUID, val proxy: String, val selected: String)

data class BackupContent(
    val createdAt: Long,
    val appVersion: String,
    /** Currently selected profile, restored only if the profile itself survived. */
    val activeProfile: String?,
    val clashFiles: Map<String, ByteArray>,
    val profiles: List<BackupProfile>,
    val chain: Map<UUID, BackupChainBinding>,
    val selections: List<BackupSelection>,
)

internal object BackupCodec {
    private const val FORMAT = "angela-backup"
    private const val VERSION = 1
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024

    fun encode(content: BackupContent, appVersion: String): ByteArray {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("createdAt", content.createdAt)
        root.put("appVersion", appVersion)
        root.put("activeProfile", content.activeProfile ?: JSONObject.NULL)

        val clash = JSONObject()
        content.clashFiles.forEach { (name, bytes) -> clash.put(name, b64(bytes)) }
        root.put("clash", clash)

        val profiles = JSONArray()
        content.profiles.forEach { profile ->
            profiles.put(
                JSONObject()
                    .put("uuid", profile.uuid.toString())
                    .put("name", profile.name)
                    .put("type", profile.type.name)
                    .put("source", profile.source)
                    .put("interval", profile.interval)
                    .put("upload", profile.upload)
                    .put("download", profile.download)
                    .put("total", profile.total)
                    .put("expire", profile.expire)
                    .put("createdAt", profile.createdAt)
                    .put("ageSecretKey", profile.ageSecretKey ?: JSONObject.NULL)
                    .put("config", b64(profile.config))
            )
        }
        root.put("profiles", profiles)

        val chain = JSONObject()
        content.chain.forEach { (uuid, binding) ->
            chain.put(
                uuid.toString(),
                JSONObject()
                    .put("entry", binding.entry)
                    .put("landing", binding.landing)
                    .put("landingProfile", binding.landingProfile)
            )
        }
        root.put("chain", chain)

        val selections = JSONArray()
        content.selections.forEach { selection ->
            selections.put(
                JSONObject()
                    .put("uuid", selection.uuid.toString())
                    .put("proxy", selection.proxy)
                    .put("selected", selection.selected)
            )
        }
        root.put("selections", selections)

        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): BackupContent {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        if (root.optString("format") != FORMAT) {
            throw IllegalArgumentException("不是 Angela Clash 备份文件")
        }
        if (root.optInt("version", 0) > VERSION) {
            throw IllegalArgumentException("备份来自更新的版本，请先升级应用")
        }

        val clash = LinkedHashMap<String, ByteArray>()
        root.optJSONObject("clash")?.let { objectValue ->
            val keys = objectValue.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                if (!clashFileName(name)) {
                    throw IllegalArgumentException("备份中含有非法文件名")
                }
                val raw = unb64(objectValue.optString(name))
                if (raw.size > MAX_FILE_BYTES) throw IllegalArgumentException("备份中的文件过大")
                clash[name] = raw
            }
        }

        val profiles = ArrayList<BackupProfile>()
        root.optJSONArray("profiles")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val type = runCatching { Profile.Type.valueOf(item.getString("type")) }
                    .getOrDefault(Profile.Type.Url)
                profiles += BackupProfile(
                    uuid = UUID.fromString(item.getString("uuid")),
                    name = item.optString("name"),
                    type = type,
                    source = item.optString("source"),
                    interval = item.optLong("interval", 0L),
                    upload = item.optLong("upload", 0L),
                    download = item.optLong("download", 0L),
                    total = item.optLong("total", 0L),
                    expire = item.optLong("expire", 0L),
                    createdAt = item.optLong("createdAt", 0L),
                    ageSecretKey = if (item.isNull("ageSecretKey")) null else item.optString("ageSecretKey"),
                    config = unb64(item.optString("config")),
                )
            }
        }

        val chain = LinkedHashMap<UUID, BackupChainBinding>()
        root.optJSONObject("chain")?.let { objectValue ->
            val keys = objectValue.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                val item = objectValue.optJSONObject(key) ?: continue
                val entry = item.optString("entry").trim()
                val landing = item.optString("landing").trim()
                val landingProfile = item.optString("landingProfile").trim()
                if (entry.isEmpty() || landing.isEmpty() || landingProfile.isEmpty()) continue
                chain[uuid] = BackupChainBinding(entry, landing, landingProfile)
            }
        }

        val selections = ArrayList<BackupSelection>()
        root.optJSONArray("selections")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val uuid = runCatching { UUID.fromString(item.optString("uuid")) }.getOrNull() ?: continue
                val proxy = item.optString("proxy")
                val selected = item.optString("selected")
                if (proxy.isNotEmpty() && selected.isNotEmpty()) {
                    selections += BackupSelection(uuid, proxy, selected)
                }
            }
        }

        return BackupContent(
            createdAt = root.optLong("createdAt", 0L),
            appVersion = root.optString("appVersion"),
            activeProfile = if (root.isNull("activeProfile")) null else root.optString("activeProfile").ifBlank { null },
            clashFiles = clash,
            profiles = profiles,
            chain = chain,
            selections = selections,
        )
    }

    /** Only plain names inside filesDir/clash are allowed; no separators, no traversal. */
    private fun clashFileName(name: String): Boolean {
        if (name.isEmpty() || name.length > 96) return false
        if (name == "." || name == "..") return false
        return name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(text: String): ByteArray =
        if (text.isEmpty()) ByteArray(0) else Base64.decode(text, Base64.NO_WRAP)
}
