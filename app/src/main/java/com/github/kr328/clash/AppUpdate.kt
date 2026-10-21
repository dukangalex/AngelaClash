package com.github.kr328.clash

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import android.os.Build
import android.widget.Toast
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AppUpdate(private val activity: BaseActivity<*>) {
    suspend fun check(silent: Boolean = false) {
        if (!silent) activity.toast(DesignR.string.check_update_running)
        val latest = withContext(Dispatchers.IO) {
            runCatching { fetchLatest() }.getOrElse {
                if (!silent) {
                    withContext(Dispatchers.Main) {
                        activity.toast(DesignR.string.check_update_failed)
                    }
                }
                null
            }
        } ?: return
        val current = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"
        if (!isNewer(latest.version, current)) {
            if (!silent) activity.toast(DesignR.string.check_update_none)
            return
        }
        withContext(Dispatchers.Main) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(DesignR.string.check_update)
                .setMessage(activity.getString(DesignR.string.check_update_found, latest.version))
                .setPositiveButton(DesignR.string.update) { _, _ -> download(latest) }
                .setNegativeButton(DesignR.string.cancel, null)
                .show()
        }
    }

    private fun download(release: Release) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
            return
        }
        val manager = activity.getSystemService(DownloadManager::class.java)
        val name = release.name.substringAfterLast('/').ifBlank { "Angela-Clash-update.apk" }
        val apk = java.io.File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
        if (apk.exists()) apk.delete()
        val request = DownloadManager.Request(Uri.parse(release.url))
            .setTitle(activity.getString(DesignR.string.check_update))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, name)
        var id = -1L
        var registered = false
        lateinit var receiver: BroadcastReceiver
        fun unregister() {
            if (registered) {
                activity.unregisterReceiver(receiver)
                registered = false
            }
        }
        fun handleCompletion() {
            val status = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                else null
            }
            when (UpdateDownloadResult.outcome(status, apk)) {
                UpdateDownloadResult.Outcome.INSTALL -> {
                    unregister()
                    install(apk)
                }
                UpdateDownloadResult.Outcome.FAILED -> {
                    unregister()
                    activity.toast(DesignR.string.check_update_failed)
                }
                UpdateDownloadResult.Outcome.WAIT -> Unit
            }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val finished = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (finished != id || id == -1L) return
                runCatching { handleCompletion() }.onFailure {
                    unregister()
                    activity.toast(DesignR.string.check_update_failed)
                }
            }
        }
        runCatching {
            activity.registerReceiverCompat(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            registered = true
            id = manager.enqueue(request)
            activity.toast(DesignR.string.check_update_downloading)
            // Also cover completion before the receiver registration/enqueue has returned.
            handleCompletion()
        }.onFailure {
            unregister()
            activity.toast(DesignR.string.check_update_failed)
        }
    }

    private fun install(apk: java.io.File) {
        if (!apk.isFile) return
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.update", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    private fun Context.toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show()
    }

    private data class Release(val version: String, val url: String, val name: String)

    private fun fetchLatest(): Release {
        val connection = (URL("https://api.github.com/repos/dukangalex/AngelaClash/releases/latest").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AngelaClash")
            connectTimeout = 15000
            readTimeout = 20000
        }
        return UpdateReleaseRequest.read(connection) { input ->
            val json = JSONObject(input.bufferedReader().readText())
            val tag = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            val candidates = (0 until assets.length()).map { index ->
                val asset = assets.getJSONObject(index)
                UpdateReleaseAssets.Asset(
                    asset.getString("name"), asset.getString("browser_download_url")
                )
            }
            val chosen = UpdateReleaseAssets.select(candidates, Build.SUPPORTED_ABIS.toList())
                ?: error("No compatible APK in release")
            Release(tag, chosen.url, chosen.name)
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val left = parts(latest)
        val right = parts(current)
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val a = left.getOrElse(i) { 0 }
            val b = right.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parts(version: String): List<Int> {
        val core = version.removePrefix("v").substringBefore("-").substringBefore(".Meta").substringBefore(".Alpha")
        return core.split('.').map { it.toIntOrNull() ?: 0 }
    }
}
