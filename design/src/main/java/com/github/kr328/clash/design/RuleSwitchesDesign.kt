package com.github.kr328.clash.design

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.google.android.material.card.MaterialCardView

class RuleSwitchesDesign(
    context: Context,
    private val store: ScriptDisplayStore,
) : Design<RuleSwitchesDesign.Request>(context) {
    enum class Request { Reload }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val density = context.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val host = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }
        host.addView(TextView(context).apply {
            text = context.getString(R.string.rule_switches_summary)
            setTextColor(context.resolveThemedColor(android.R.attr.textColorSecondary))
            textSize = 13f
            setPadding(dp(4), 0, dp(4), dp(10))
        })
        val rows = store.snapshot().sections.firstOrNull()?.rows.orEmpty()
        if (rows.isEmpty()) {
            host.addView(TextView(context).apply {
                text = context.getString(R.string.rule_switches_empty)
                setTextColor(context.resolveThemedColor(android.R.attr.textColorSecondary))
                textSize = 14f
                setPadding(dp(4), dp(16), dp(4), 0)
            })
        }
        for (row in rows) {
            host.addView(switchCard(store, row.name, row.on))
        }
        binding.content.addView(host)
    }

    private fun switchCard(
        store: ScriptDisplayStore,
        name: String,
        on: Boolean,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val title = TextView(context).apply {
            text = name
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val mark = ImageView(context).apply {
            setImageResource(R.drawable.ic_rule_switches)
            setColorFilter(context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary))
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(12)
            }
        }
        val toggle = SwitchCompat(context).apply {
            isChecked = on
            setOnCheckedChangeListener { _, checked ->
                store.setOption(ScriptDisplayStore.GROUP_RULE, name, checked)
                requests.trySend(Request.Reload)
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            addView(mark)
            addView(title)
            addView(toggle)
        }
        return MaterialCardView(context).apply {
            radius = 18f * context.resources.displayMetrics.density
            cardElevation = 0f
            setCardBackgroundColor(context.resolveThemedColor(R.attr.clashSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            addView(row)
        }
    }
}
