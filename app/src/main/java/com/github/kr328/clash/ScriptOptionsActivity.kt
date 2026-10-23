package com.github.kr328.clash

import android.text.InputType
import android.widget.EditText
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ScriptOptionsDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.util.withProfile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

class ScriptOptionsActivity : BaseActivity<ScriptOptionsDesign>() {
    override suspend fun main() {
        val store = ScriptDisplayStore(this)
        val design = ScriptOptionsDesign(this, store, clashRunning)
        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
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
                        ScriptOptionsDesign.Request.Reload ->
                            reloadRunning()
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
