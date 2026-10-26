package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.design.store.UiStore
import com.google.android.material.card.MaterialCardView
import java.util.UUID

/**
 * 当前配置的策略组开关。
 *
 * 策略组属于配置本身：只有在该配置启用了覆写脚本时，这些开关才会改写它的
 * 策略组与分流规则；否则订阅原样生效，开关处于不可用状态并明确说明原因。
 */
class RuleSwitchesDesign(
    context: Context,
    private val store: ScriptDisplayStore,
    private val profileUuid: UUID?,
    private val profileName: String?,
    scriptOn: Boolean,
    groups: List<String>,
    private val uiStore: UiStore = UiStore(context),
) : Design<RuleSwitchesDesign.Request>(context) {
    sealed class Request {
        object Reload : Request()
        data class ToggleScript(val enabled: Boolean) : Request()
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)
    private val host = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(24))
    }
    private var scriptEnabled = scriptOn
    private var profileGroups: List<String> = groups

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        binding.content.addView(host)
        render()
    }

    fun setScriptEnabled(enabled: Boolean) {
        scriptEnabled = enabled
        render()
    }

    /** Groups the running config exposes for the active profile. */
    fun updateGroups(groups: List<String>) {
        profileGroups = groups
        if (!scriptEnabled) {
            render()
        }
    }

    private fun render() {
        host.removeAllViews()
        host.addView(headerCard())

        if (profileUuid == null) {
            host.addView(noteCard(getString(R.string.rule_switches_no_profile), accent = true))
            return
        }

        if (!scriptEnabled) {
            renderProfileGroups()
            return
        }

        val rows = store.snapshot().sections.firstOrNull()?.rows.orEmpty()
        if (rows.isEmpty()) {
            host.addView(noteCard(getString(R.string.rule_switches_empty), accent = false))
            return
        }
        host.addView(noteCard(getString(R.string.rule_switches_summary), accent = false))

        // 用户可以把不常用的开关收进「其他设置」，但不会真的消失 ——
        // 这里只决定「放在主列表还是折叠区」，开关本身一个都不少。
        val hidden = uiStore.hiddenRuleSwitches
        val visibleRows = rows.filter { it.name !in hidden }
        val foldedRows = rows.filter { it.name in hidden }

        for (row in visibleRows) {
            host.addView(switchCard(row.name, row.on))
        }

        if (visibleRows.size != rows.size) {
            host.addView(foldedSection(foldedRows))
        }

        host.addView(manageRow(rows.map { it.name }))
    }

    /** 被收起的开关：默认折叠，点开才展开，标题上标出数量。 */
    private fun foldedSection(rows: List<ScriptDisplayStore.Row>): View {
        val secondary = context.resolveThemedColor(android.R.attr.textColorSecondary)

        val title = TextView(context).apply {
            text = getString(R.string.rule_switches_other)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(secondary)
        }
        val count = TextView(context).apply {
            text = context.getString(R.string.rule_switches_other_count, rows.size)
            textSize = 12f
            setTextColor(secondary)
            setPadding(dp(10), 0, 0, 0)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(title)
            addView(count)
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            for (row in rows) addView(switchCard(row.name, row.on, flat = true))
        }
        header.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        return card(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                addView(body)
            },
            stroke = null,
        )
    }

    /** 底部一行入口：勾选哪些开关留在主列表。 */
    private fun manageRow(names: List<String>): View {
        val accent = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val text = TextView(context).apply {
            this.text = getString(R.string.rule_switches_manage)
            textSize = 13f
            setTextColor(accent)
            setPadding(dp(8), dp(14), dp(8), dp(10))
        }
        text.setOnClickListener { showManageDialog(names) }
        return text
    }

    private fun showManageDialog(names: List<String>) {
        if (names.isEmpty()) return
        val hidden = uiStore.hiddenRuleSwitches
        val checked = names.map { it !in hidden }.toBooleanArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_switches_manage)
            .setMessage(R.string.rule_switches_manage_hint)
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                uiStore.hiddenRuleSwitches = names
                    .filterIndexed { index, _ -> !checked[index] }
                    .toSet()
                render()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 这份配置没开脚本时，这一页显示的是**它自己的**规则结果：运行配置里真实存在的
     * 策略组。没有脚本就没有可拨的开关，只有订阅原本的结构，读一眼就知道"原规则"是什么。
     */
    private fun renderProfileGroups() {
        host.addView(noteCard(getString(R.string.rule_switches_script_off), accent = true))

        if (profileGroups.isEmpty()) {
            host.addView(noteCard(getString(R.string.rule_switches_native_empty), accent = false))
            return
        }

        host.addView(noteCard(getString(R.string.rule_switches_native_summary), accent = false))
        profileGroups.forEach { host.addView(groupCard(it)) }
    }

    /** Read-only card: the group exists in this profile's config, nothing to toggle. */
    private fun groupCard(name: String): View {
        val primaryText = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val secondaryText = context.resolveThemedColor(android.R.attr.textColorSecondary)

        val title = TextView(context).apply {
            text = name
            textSize = 15f
            setTextColor(primaryText)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val badge = TextView(context).apply {
            text = getString(R.string.rule_switches_native_badge)
            textSize = 11f
            setTextColor(secondaryText)
            setPadding(dp(10), 0, 0, 0)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(badge)
        }
        return card(row, stroke = null)
    }

    /** Current profile + the switch that decides whether these toggles apply to it. */
    private fun headerCard(): View {
        val primary = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val secondary = context.resolveThemedColor(android.R.attr.textColorSecondary)

        val title = TextView(context).apply {
            text = profileName ?: getString(R.string.rule_switches_no_profile)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primary)
        }
        val subtitle = TextView(context).apply {
            text = getString(
                if (scriptEnabled) R.string.rule_switches_profile_on else R.string.rule_switches_profile_off,
            )
            textSize = 12f
            setTextColor(secondary)
            setPadding(0, dp(3), 0, 0)
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(subtitle)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val toggle = SwitchCompat(context).apply {
            isChecked = scriptEnabled
            isEnabled = profileUuid != null
            contentDescription = getString(R.string.rule_switches_script_toggle)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                if (checked != scriptEnabled) {
                    requests.trySend(Request.ToggleScript(checked))
                }
            }
        }
        return card(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(12), dp(12))
                addView(labels)
                addView(toggle)
            },
            stroke = color(context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary), 120),
        )
    }

    private fun noteCard(text: String, accent: Boolean): View {
        val view = TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(
                if (accent) {
                    context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
                } else {
                    context.resolveThemedColor(android.R.attr.textColorSecondary)
                },
            )
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        return card(view, stroke = null)
    }

    private fun switchCard(name: String, on: Boolean, flat: Boolean = false): View {
        val primaryText = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val secondaryText = context.resolveThemedColor(android.R.attr.textColorSecondary)
        val colorPrimary = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val activeStroke = ColorUtils.setAlphaComponent(colorPrimary, 140)
        val inactiveStroke = context.resolveThemedColor(R.attr.clashOutline)

        val enabled = scriptEnabled && name.isNotBlank()

        val title = TextView(context).apply {
            text = name
            textSize = 15f
            setTextColor(primaryText)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val summary = store.snapshotSummary(name)?.let { text ->
            TextView(context).apply {
                this.text = text
                textSize = 12f
                setTextColor(secondaryText)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            summary?.let { addView(it) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        lateinit var cardView: MaterialCardView
        val toggle = SwitchCompat(context).apply {
            isChecked = on
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            contentDescription = name
            minHeight = dp(48)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                if (!enabled) return@setOnCheckedChangeListener
                cardView.setStrokeColor(if (checked) activeStroke else inactiveStroke)
                store.setOption(ScriptDisplayStore.GROUP_RULE, name, checked)
                requests.trySend(Request.Reload)
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            isClickable = enabled
            isFocusable = enabled
            addView(labels)
            addView(toggle)
            if (enabled) {
                setOnClickListener { toggle.isChecked = !toggle.isChecked }
            }
            alpha = if (enabled) 1f else 0.55f
        }

        if (flat) {
            // 折叠区里已经有一层卡片了，这里只画一行内容，避免卡片套卡片。
            return row.apply {
                setPadding(0, dp(4), dp(8), dp(4))
            }
        }

        cardView = card(
            row,
            stroke = if (on && enabled) activeStroke else inactiveStroke,
        )
        return cardView
    }

    private fun card(content: View, stroke: Int?): MaterialCardView =
        MaterialCardView(context).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(context.resolveThemedColor(R.attr.clashSurfaceVariant))
            strokeWidth = dp(1)
            setStrokeColor(stroke ?: context.resolveThemedColor(R.attr.clashOutline))
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }

    private fun color(value: Int, alpha: Int): Int =
        ColorUtils.setAlphaComponent(value, alpha)

    private fun getString(id: Int): String = context.getString(id)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
