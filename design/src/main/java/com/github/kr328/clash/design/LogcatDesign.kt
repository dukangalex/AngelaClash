package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.adapter.LogMessageAdapter
import com.github.kr328.clash.design.databinding.DesignLogcatBinding
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogcatDesign(
    context: Context,
    private val streaming: Boolean,
) : Design<LogcatDesign.Request>(context) {
    enum class Request {
        Close, Delete, Export, History
    }

    private val binding = DesignLogcatBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = LogMessageAdapter(context) {
        launch {
            val data = ClipData.newPlainText("log_message", it.message)

            context.getSystemService<ClipboardManager>()?.setPrimaryClip(data)

            showToast(R.string.copied, ToastDuration.Short)
        }
    }
    private var source: List<LogMessage> = emptyList()
    private var floor: LogMessage.Level? = null

    suspend fun patchMessages(messages: List<LogMessage>, removed: Int, appended: Int) {
        withContext(Dispatchers.Main) {
            source = messages
            showFiltered(scroll = streaming && binding.recyclerList.isTop)
        }
    }

    private fun showFiltered(scroll: Boolean) {
        val shown = source.filter { message ->
            val floor = floor ?: return@filter true
            message.level.ordinal >= floor.ordinal && message.level != LogMessage.Level.Silent
        }
        adapter.messages = shown
        adapter.notifyDataSetChanged()
        if (scroll && shown.isNotEmpty()) {
            binding.recyclerList.scrollToPosition(shown.lastIndex)
        }
    }

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.streaming = streaming

        binding.activityBarLayout.applyFrom(context)
        binding.recyclerList.bindAppBarElevation(binding.activityBarLayout)

        binding.recyclerList.layoutManager = LinearLayoutManager(context).apply {
            if (streaming) {
                reverseLayout = true
                stackFromEnd = true
            }
        }
        binding.recyclerList.adapter = adapter

        val chips = listOf(
            binding.filterAll to null,
            binding.filterDebug to LogMessage.Level.Debug,
            binding.filterInfo to LogMessage.Level.Info,
            binding.filterWarning to LogMessage.Level.Warning,
            binding.filterError to LogMessage.Level.Error,
        )
        fun paint() {
            for ((view, level) in chips) {
                val selected = floor == level
                view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                view.setTextColor(if (selected) Color.WHITE else 0xFF8A93A6.toInt())
                val chip = android.graphics.drawable.GradientDrawable()
                chip.cornerRadius = 16 * view.resources.displayMetrics.density
                chip.setColor(if (selected) 0xFF2563EB.toInt() else 0xFF171C24.toInt())
                view.background = chip
            }
        }
        for ((view, level) in chips) {
            view.setOnClickListener {
                floor = level
                paint()
                showFiltered(scroll = false)
            }
        }
        paint()
    }
}