package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.design.util.format
import com.github.kr328.clash.design.view.ActionLabel

class LogFileAdapter(
    private val context: Context,
    private val open: (LogFile) -> Unit,
) : RecyclerView.Adapter<LogFileAdapter.Holder>() {
    class Holder(val label: ActionLabel) : RecyclerView.ViewHolder(label)

    var logs: List<LogFile> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ActionLabel(context).apply {
            val density = context.resources.displayMetrics.density
            val margin = (16 * density).toInt()
            val gap = (4 * density).toInt()
            layoutParams = ViewGroup.MarginLayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(margin, gap, margin, gap)
            }
            setBackgroundResource(R.drawable.bg_b)
        })
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = logs[position]

        holder.label.text = current.fileName
        holder.label.subtext = current.date.format(context)
        holder.label.setOnClickListener {
            open(current)
        }
    }

    override fun getItemCount(): Int {
        return logs.size
    }
}