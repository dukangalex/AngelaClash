package com.github.kr328.clash.design.adapter

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.databinding.AdapterLogMessageBinding
import com.github.kr328.clash.design.util.layoutInflater

class LogMessageAdapter(
    private val context: Context,
    private val copy: (LogMessage) -> Unit,
) :
    RecyclerView.Adapter<LogMessageAdapter.Holder>() {
    class Holder(val binding: AdapterLogMessageBinding) : RecyclerView.ViewHolder(binding.root)

    var messages: List<LogMessage> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterLogMessageBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = messages[position]

        holder.binding.message = current
        holder.binding.level.setTextColor(when (current.level) {
            LogMessage.Level.Debug -> 0xFF8E8E93.toInt()
            LogMessage.Level.Info -> 0xFF2F6FED.toInt()
            LogMessage.Level.Warning -> 0xFFE6A23C.toInt()
            LogMessage.Level.Error -> 0xFFE25454.toInt()
            else -> Color.GRAY
        })
        holder.binding.root.setOnLongClickListener {
            copy(current)

            true
        }
    }

    override fun getItemCount(): Int {
        return messages.size
    }
}