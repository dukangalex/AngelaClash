package com.github.kr328.clash

import android.text.InputType
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.design.EnhanceToolsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.backup.BackupManager
import com.github.kr328.clash.service.backup.WrongPassphraseException
import com.github.kr328.clash.service.store.ServiceStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class EnhanceToolsActivity : BaseActivity<EnhanceToolsDesign>() {
    override suspend fun main() {
        val design = EnhanceToolsDesign(this, ServiceStore(this))
        setContentDesign(design)
        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive {
                    when (it) {
                        EnhanceToolsDesign.Request.ExportBackup -> exportBackup(design)
                        EnhanceToolsDesign.Request.RestoreBackup -> restoreBackup(design)
                    }
                }
            }
        }
    }

    private suspend fun exportBackup(design: EnhanceToolsDesign) {
        val passphrase = askPassphrase(
            design = design,
            title = R.string.backup_export,
            message = R.string.backup_passphrase_message,
        ) ?: return

        try {
            val suggested = "angela-backup-" +
                SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".angb"

            val uri = startActivityForResult(
                ActivityResultContracts.CreateDocument("application/octet-stream"),
                suggested,
            ) ?: return

            val versionName = withContext(Dispatchers.IO) {
                runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                }.getOrDefault("0")
            }

            val summary = runCatching {
                withContext(Dispatchers.IO) {
                    val stream = contentResolver.openOutputStream(uri)
                        ?: throw IOException(getString(R.string.backup_export_open_failed))
                    stream.use {
                        BackupManager(this@EnhanceToolsActivity)
                            .exportTo(it, passphrase, versionName)
                    }
                }
            }

            summary.onSuccess {
                design.showToast(
                    getString(R.string.backup_export_done, it.profiles, it.clashFiles),
                    ToastDuration.Long,
                )
            }.onFailure { error ->
                design.showToast(
                    getString(R.string.backup_failed, describe(error)),
                    ToastDuration.Long,
                )
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private suspend fun restoreBackup(design: EnhanceToolsDesign) {
        val uri = startActivityForResult(
            ActivityResultContracts.OpenDocument(),
            arrayOf("application/octet-stream", "*/*"),
        ) ?: return

        val passphrase = askPassphrase(
            design = design,
            title = R.string.backup_restore,
            message = R.string.backup_restore_message,
        ) ?: return

        try {
            val restored = runCatching {
                withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri)
                        ?: throw IOException(getString(R.string.backup_restore_open_failed))
                    stream.use {
                        BackupManager(this@EnhanceToolsActivity).restoreFrom(it, passphrase)
                    }
                }
            }

            restored.onSuccess {
                design.showToast(
                    getString(
                        R.string.backup_restore_done,
                        it.summary.profiles,
                        it.summary.selections,
                    ),
                    ToastDuration.Long,
                )
            }.onFailure { error ->
                val message = if (error is WrongPassphraseException) {
                    getString(R.string.backup_wrong_passphrase)
                } else {
                    getString(R.string.backup_failed, describe(error))
                }
                design.showToast(message, ToastDuration.Long)
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private fun describe(error: Throwable): String =
        error.message ?: error.javaClass.simpleName

    private suspend fun askPassphrase(
        design: EnhanceToolsDesign,
        title: Int,
        message: Int,
    ): CharArray? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val input = EditText(this@EnhanceToolsActivity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine()
                hint = getString(R.string.backup_passphrase_hint)
            }

            val dialog = MaterialAlertDialogBuilder(this@EnhanceToolsActivity)
                .setTitle(title)
                .setMessage(message)
                .setView(input)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (continuation.isActive) continuation.resume(null)
                }
                .setPositiveButton(R.string.backup_passphrase_confirm) { _, _ ->
                    val text = input.text?.toString() ?: ""
                    if (text.length < BackupManager.MIN_PASSPHRASE) {
                        // Tell the user why nothing happened, then stop: a shorter
                        // passphrase protects nothing on a file that holds tokens.
                        launchToast(
                            design,
                            getString(R.string.backup_passphrase_short, BackupManager.MIN_PASSPHRASE),
                        )
                        if (continuation.isActive) continuation.resume(null)
                    } else if (continuation.isActive) {
                        continuation.resume(text.toCharArray())
                    }
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resume(null)
                }
                .create()

            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    }

    private fun launchToast(design: EnhanceToolsDesign, message: String) {
        launch {
            design.showToast(message, ToastDuration.Long)
        }
    }
}
