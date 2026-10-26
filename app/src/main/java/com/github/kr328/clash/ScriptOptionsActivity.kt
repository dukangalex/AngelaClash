package com.github.kr328.clash

import android.text.InputType
import android.widget.EditText
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ScriptOptionsDesign
import com.github.kr328.clash.design.ui.AppEvents
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.util.withProfile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume

class ScriptOptionsActivity : BaseActivity<ScriptOptionsDesign>() {
    /** 当前生效配置的 uuid：用来判断 ScriptToggled 是不是关于「这一份」的。 */
    private var activeUuid: java.util.UUID? = null

    private suspend fun refreshProfile(design: ScriptOptionsDesign, store: ScriptDisplayStore) {
        val active = withProfile { queryActive() }
        activeUuid = active?.uuid
        design.showProfile(
            name = active?.name,
            scriptOn = active?.let { store.profileScriptEnabled(it.uuid) },
        )
    }

    override suspend fun main() {
        val store = ScriptDisplayStore(this)
        val design = ScriptOptionsDesign(this, store, clashRunning)
        setContentDesign(design)

        refreshProfile(design, store)

        // 别的页面（首页 chip / 规则开关页）改了同一批开关：这边跟着重画，
        // 不用等用户退出去再进来。
        // 注意用 launch 而不是塞进下面的 select —— SharedFlow 是多订阅者，
        // 塞进 select 会被其中一个分支「吃掉」。
        launch {
            AppEvents.events.collect { event ->
                when (event) {
                    is AppEvents.Event.OptionsChanged -> design.reload(store)
                    AppEvents.Event.ScriptContentChanged -> design.reload(store)
                    is AppEvents.Event.ScriptToggled -> {
                        if (event.uuid == activeUuid) {
                            design.setScriptOn(event.enabled)
                        }
                    }
                }
            }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        // 以前这里是 recreate()：整页重建，滚动位置和展开的分组全丢。
                        // 现在只切换「运行中」那条提醒。
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            design.setRunning(clashRunning)
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ScriptOptionsDesign.Request.EditScript ->
                            startActivity(ScriptEditorActivity::class.intent)
                        ScriptOptionsDesign.Request.ImportLink ->
                            importLink(store, design)
                        ScriptOptionsDesign.Request.Sync ->
                            sync(store, design)
                        ScriptOptionsDesign.Request.Reload -> {
                            reloadRunning()
                            AppEvents.emit(AppEvents.Event.OptionsChanged(ScriptDisplayStore.GROUP_RULE))
                        }
                        is ScriptOptionsDesign.Request.ToggleScript -> {
                            val current = withProfile { queryActive() }
                            if (current != null) {
                                activeUuid = current.uuid
                                store.setProfileScriptEnabled(current.uuid, it.enabled)
                                design.setScriptOn(it.enabled)
                                AppEvents.emit(AppEvents.Event.ScriptToggled(current.uuid, it.enabled))
                                if (clashRunning) sendProfileChanged(current.uuid)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun importLink(store: ScriptDisplayStore, design: ScriptOptionsDesign) {
        val typed = askUrl(store.sourceUrl()) ?: return
        val url = typed.trim()
        if (url.isEmpty()) {
            store.setSource("")
            design.reload(store)
            design.showToast(R.string.script_source_cleared, ToastDuration.Short)
            return
        }
        pull(store, design, url)
    }

    private suspend fun sync(store: ScriptDisplayStore, design: ScriptOptionsDesign) {
        val url = store.sourceUrl().trim()
        if (url.isEmpty()) {
            importLink(store, design)
            return
        }
        pull(store, design, url)
    }

    private suspend fun pull(store: ScriptDisplayStore, design: ScriptOptionsDesign, url: String) {
        design.showSource(url, store.syncedAt(), true)
        val text = try {
            fetchScript(url)
        } catch (e: IllegalArgumentException) {
            design.showSource(store.sourceUrl(), store.syncedAt(), false)
            design.showToast(e.message ?: getString(R.string.script_import_bad_url), ToastDuration.Long)
            return
        } catch (e: Exception) {
            design.showSource(store.sourceUrl(), store.syncedAt(), false)
            design.showToast(getString(R.string.script_import_failed, e.message ?: ""), ToastDuration.Long)
            return
        }
        // A script can rewrite the whole runtime config, so a silent change of
        // content at an already trusted URL is worth one confirmation.
        val nextHash = sha256(text)
        val previousHash = store.syncedSha256()
        if (store.sourceUrl() == url && previousHash.isNotEmpty() && previousHash != nextHash) {
            val accepted = confirmSourceChanged(url, previousHash, nextHash)
            if (!accepted) {
                design.showSource(store.sourceUrl(), store.syncedAt(), false)
                design.showToast(R.string.script_import_cancelled, ToastDuration.Short)
                return
            }
        }
        store.writeFetched(text, url)
        design.reload(store)
        reloadRunning()
        design.showToast(R.string.script_imported, ToastDuration.Short)
    }

    private suspend fun reloadRunning() {
        val active = withProfile { queryActive() }
        if (active != null && clashRunning) sendProfileChanged(active.uuid)
    }

    private suspend fun askUrl(current: String): String? = suspendCancellableCoroutine { cont ->
        val input = EditText(this).apply {
            setText(current)
            hint = getString(R.string.script_source_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setSelection(text.length)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.script_import_link)
            .setView(input)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (cont.isActive) cont.resume(null)
            }
            .setPositiveButton(R.string.script_import_link) { _, _ ->
                if (cont.isActive) cont.resume(input.text?.toString().orEmpty())
            }
            .setOnCancelListener {
                if (cont.isActive) cont.resume(null)
            }
            .create()
        cont.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }

    private suspend fun confirmSourceChanged(url: String, previous: String, next: String): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val dialog = MaterialAlertDialogBuilder(this@ScriptOptionsActivity)
                    .setTitle(R.string.script_source_changed_title)
                    .setMessage(
                        getString(
                            R.string.script_source_changed_message,
                            url,
                            previous.take(12),
                            next.take(12),
                        )
                    )
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        if (continuation.isActive) continuation.resume(false)
                    }
                    .setPositiveButton(R.string.script_source_changed_continue) { _, _ ->
                        if (continuation.isActive) continuation.resume(true)
                    }
                    .setOnCancelListener {
                        if (continuation.isActive) continuation.resume(false)
                    }
                    .create()
                continuation.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
            }
        }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private suspend fun fetchScript(raw: String): String = withContext(Dispatchers.IO) {
        var current = normalize(raw)
        var redirects = 0

        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "AngelaClash")
                setRequestProperty("Accept", "text/plain, application/javascript, */*")
            }

            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    if (redirects >= MAX_REDIRECTS) {
                        throw IllegalStateException("Too many redirects")
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect has no Location")
                    current = normalize(URL(current, location).toExternalForm())
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }

                val bytes = connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_SCRIPT) {
                            throw IllegalArgumentException(getString(R.string.script_import_large))
                        }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                val head = text.trimStart()
                if (head.isEmpty()) {
                    throw IllegalArgumentException(getString(R.string.script_import_empty))
                }
                val lower = head.take(32).lowercase()
                if (lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.startsWith("<head")) {
                    throw IllegalArgumentException(getString(R.string.script_import_not_script))
                }
                return@withContext text
            } finally {
                connection.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("Unable to fetch script")
    }

    private fun normalize(raw: String): URL {
        val text = raw.trim()
        val withScheme = if ("://" in text) text else "https://$text"
        val url = try {
            URL(withScheme)
        } catch (_: Exception) {
            throw IllegalArgumentException(getString(R.string.script_import_bad_url))
        }
        val uri = try {
            url.toURI()
        } catch (_: Exception) {
            throw IllegalArgumentException(getString(R.string.script_import_bad_url))
        }
        if (!url.protocol.equals("https", ignoreCase = true) ||
            url.host.isNullOrBlank() ||
            uri.userInfo != null
        ) {
            throw IllegalArgumentException(getString(R.string.script_import_bad_url))
        }
        return url
    }

    companion object {
        private const val MAX_SCRIPT = 512 * 1024
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}
