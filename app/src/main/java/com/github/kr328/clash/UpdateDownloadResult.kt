package com.github.kr328.clash

import android.app.DownloadManager
import java.io.File

/** A completion broadcast alone is not evidence that an APK downloaded successfully. */
internal object UpdateDownloadResult {
    enum class Outcome { WAIT, INSTALL, FAILED }

    fun outcome(status: Int?, apk: File): Outcome = when (status) {
        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> Outcome.WAIT
        DownloadManager.STATUS_SUCCESSFUL -> if (apk.isFile && apk.length() > 0L) Outcome.INSTALL else Outcome.FAILED
        else -> Outcome.FAILED
    }
}
