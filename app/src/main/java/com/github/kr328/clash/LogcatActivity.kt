package com.github.kr328.clash

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.fileName
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.LogcatDesign
import com.github.kr328.clash.design.dialog.withModelProgressBar
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.log.LogcatFilter
import com.github.kr328.clash.log.LogcatReader
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.util.logsDir
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import com.github.kr328.clash.design.R

class LogcatActivity : BaseActivity<LogcatDesign>() {
    override suspend fun main() {
        stopService(LogcatService::class.intent)
        val fileName = intent?.fileName

        if (fileName != null) {
            val file = LogFile.parseFromFileName(fileName) ?: return showInvalid()

            return mainLocalFile(file)
        }

        return mainStreaming()
    }

    private suspend fun mainLocalFile(file: LogFile) {
        val messages = try {
            LogcatReader(this, file).readAll()
        } catch (e: Exception) {
            Log.e("Fail to read log file ${file.fileName}: ${e.message}")
            return showInvalid()
        }

        val design = LogcatDesign(this, false)

        setContentDesign(design)

        design.patchMessages(messages, 0, messages.size)

        while (isActive) {
            when (design.requests.receive()) {
                LogcatDesign.Request.Delete -> {
                    withContext(Dispatchers.IO) {
                        logsDir.resolve(file.fileName).delete()
                    }

                    finish()
                }
                LogcatDesign.Request.Export -> {
                    val output = startActivityForResult(
                        ActivityResultContracts.CreateDocument("text/plain"),
                        file.fileName
                    )

                    if (output != null) {
                        try {
                            withContext(Dispatchers.IO) {
                                writeLogTo(messages, file, output)
                            }

                            design.showToast(R.string.file_exported, ToastDuration.Long)
                        } catch (e: Exception) {
                            design.showExceptionToast(e)
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private suspend fun mainStreaming() {
        val design = LogcatDesign(this, true)
        setContentDesign(design)

        val messages = ArrayDeque<LogMessage>()
        val incoming = Channel<LogMessage>(128)
        withClash {
            setLogObserver(object : ILogObserver {
                override fun newItem(log: LogMessage) {
                    incoming.trySend(log)
                }
            })
        }

        try {
            while (isActive) {
                select<Unit> {
                    events.onReceive { }
                    design.requests.onReceive {
                        when (it) {
                            LogcatDesign.Request.Close -> finish()
                            LogcatDesign.Request.History ->
                                startActivity(LogsActivity::class.intent)
                            else -> Unit
                        }
                    }
                    incoming.onReceive { message ->
                        messages.addLast(message)
                        while (messages.size > 500) messages.removeFirst()
                        design.patchMessages(messages.toList(), 0, messages.size)
                    }
                }
            }
        } finally {
            withClash { setLogObserver(null) }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun writeLogTo(messages: List<LogMessage>, file: LogFile, uri: Uri) {
        LogcatFilter(OutputStreamWriter(contentResolver.openOutputStream(uri)), this).use {
            withContext(Dispatchers.Main) {
                withModelProgressBar {
                    configure {
                        isIndeterminate = true
                        max = messages.size
                    }

                    withContext(Dispatchers.IO) {
                        it.writeHeader(file.date)

                        messages.forEachIndexed { idx, msg ->
                            configure {
                                isIndeterminate = false
                                progress = idx
                            }

                            it.writeMessage(msg)
                        }
                    }
                }
            }
        }
    }

    private fun showInvalid() {
        Toast.makeText(this, R.string.invalid_log_file, Toast.LENGTH_LONG).show()
    }
}