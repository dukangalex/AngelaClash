package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.github.kr328.clash.design.databinding.DesignScriptOptionsBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.google.android.material.card.MaterialCardView

class ScriptOptionsDesign(
    context: Context,
    store: ScriptDisplayStore,
    running: Boolean,
) : Design<ScriptOptionsDesign.Request>(context) {
    enum class Request {
        EditScript,
        Reload,
    }

    private val binding = DesignScriptOptionsBinding
        .inflate(context.layoutInflater, context.root, false)
    private var ruleRows: List<ScriptDisplayStore.Row> = emptyList()
    private val ruleSwitches = mutableListOf<SwitchCompat>()

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        styleOutlinedCard(binding.scriptEnableCard, 20)
        styleOutlinedCard(binding.editorCard, 20)
        styleOutlinedCard(binding.runtimeNotice, 16, accent = true)
        binding.runtimeNotice.visibility = if (running) View.VISIBLE else View.GONE

        val snap = store.snapshot()
        binding.scriptEnableSwitch.isChecked = snap.scriptEnabled
        binding.scriptEnableSwitch.setOnCheckedChangeListener { _, enabled ->
            store.setScriptEnabled(enabled)
            requests.trySend(Request.Reload)
        }
        binding.scriptEnableRow.setOnClickListener {
            binding.scriptEnableSwitch.isChecked = !binding.scriptEnableSwitch.isChecked
        }

        ruleRows = snap.sections.firstOrNull()?.rows.orEmpty()
        binding.ruleCard.visibility = View.VISIBLE
        binding.ruleEmpty.visibility = if (ruleRows.isEmpty()) View.VISIBLE else View.GONE
        binding.ruleCount.visibility = if (ruleRows.isEmpty()) View.GONE else View.VISIBLE
        renderRuleRows(store)
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    private fun renderRuleRows(store: ScriptDisplayStore) {
        binding.ruleRows.removeAllViews()
        ruleSwitches.clear()
        ruleRows.forEachIndexed { index, row ->
            binding.ruleRows.addView(ruleRow(store, row, index))
        }
        updateRuleCount()
    }

    private fun ruleRow(store: ScriptDisplayStore, row: ScriptDisplayStore.Row, index: Int): View {
        val displayName = row.name.ifBlank {
            context.getString(R.string.script_option_unnamed, index + 1)
        }
        val primaryText = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val secondaryText = context.resolveThemedColor(android.R.attr.textColorSecondary)
        val title = TextView(context).apply {
            text = displayName
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(primaryText)
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)

            row.summary?.takeIf { it.isNotBlank() }?.let { summaryText ->
                addView(TextView(context).apply {
                    text = summaryText
                    textSize = 13f
                    setTextColor(secondaryText)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(3)
                    }
                })
            }
        }
        val toggle = SwitchCompat(context).apply {
            isChecked = row.on
            contentDescription = buildString {
                append(displayName)
                if (!row.summary.isNullOrBlank()) {
                    append(". ")
                    append(row.summary)
                }
            }
            minHeight = dp(48)
            isEnabled = row.name.isNotBlank()
            setOnCheckedChangeListener { _, enabled ->
                store.setOption(ScriptDisplayStore.GROUP_RULE, row.name, enabled)
                updateRuleCount()
                requests.trySend(Request.Reload)
            }
        }
        ruleSwitches += toggle

        val item = FrameLayout(context).apply {
            minimumHeight = dp(72)
            isClickable = true
            isFocusable = true
            addView(labels, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL,
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(76)
                topMargin = dp(10)
                bottomMargin = dp(10)
            })
            addView(toggle, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ).apply { marginEnd = dp(12) })
            setOnClickListener {
                if (toggle.isEnabled) toggle.isChecked = !toggle.isChecked
            }
        }

        return MaterialCardView(context).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(surfaceColor())
            strokeWidth = dp(1)
            setStrokeColor(context.resolveThemedColor(R.attr.clashOutline))
            addView(item, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
        }
    }

    private fun styleOutlinedCard(card: MaterialCardView, radiusDp: Int, accent: Boolean = false) {
        val surface = surfaceColor()
        card.setCardBackgroundColor(surface)
        card.radius = dp(radiusDp).toFloat()
        card.cardElevation = 0f
        card.strokeWidth = dp(1)
        card.setStrokeColor(
            if (accent) {
                context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
            } else {
                val secondary = context.resolveThemedColor(android.R.attr.textColorSecondary)
                val alpha = if (ColorUtils.calculateLuminance(surface) < 0.5) 0.25f else 0.45f
                ColorUtils.setAlphaComponent(secondary, (Color.alpha(secondary) * alpha).toInt())
            },
        )
    }

    private fun surfaceColor(): Int = context.resolveThemedColor(
        R.attr.clashSurfaceVariant,
    )

    private fun updateRuleCount() {
        binding.ruleCount.text = context.getString(
            R.string.script_rule_count_format,
            ruleSwitches.count { it.isChecked },
            ruleRows.size,
        )
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
