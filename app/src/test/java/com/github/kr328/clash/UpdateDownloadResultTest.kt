package com.github.kr328.clash

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadResultTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedDownloadNeverInstallsPartialApk() {
        val apk = temporary.newFile().apply { writeText("partial download") }
        assertEquals(UpdateDownloadResult.Outcome.FAILED, UpdateDownloadResult.outcome(DownloadManager.STATUS_FAILED, apk))
    }
    @Test fun successfulNonemptyApkCanInstall() {
        val apk = temporary.newFile().apply { writeText("fixture, not a real APK") }
        assertEquals(UpdateDownloadResult.Outcome.INSTALL, UpdateDownloadResult.outcome(DownloadManager.STATUS_SUCCESSFUL, apk))
    }
    @Test fun successfulButEmptyDownloadFails() {
        assertEquals(UpdateDownloadResult.Outcome.FAILED, UpdateDownloadResult.outcome(DownloadManager.STATUS_SUCCESSFUL, temporary.newFile()))
    }
    @Test fun successfulButMissingDownloadFails() {
        assertEquals(UpdateDownloadResult.Outcome.FAILED, UpdateDownloadResult.outcome(DownloadManager.STATUS_SUCCESSFUL, java.io.File(temporary.root, "missing.apk")))
    }
    @Test fun directoriesCannotInstall() {
        assertEquals(UpdateDownloadResult.Outcome.FAILED, UpdateDownloadResult.outcome(DownloadManager.STATUS_SUCCESSFUL, temporary.newFolder()))
    }
    @Test fun nonterminalStatesWait() {
        for (status in listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)) {
            assertEquals(UpdateDownloadResult.Outcome.WAIT, UpdateDownloadResult.outcome(status, temporary.newFile()))
        }
    }
    @Test fun missingOrUnknownDownloadFails() {
        assertEquals(UpdateDownloadResult.Outcome.FAILED, UpdateDownloadResult.outcome(null, temporary.newFile()))
        assertEquals(UpdateDownloadResult.Outcome.FAILED, UpdateDownloadResult.outcome(-99, temporary.newFile()))
    }
}
