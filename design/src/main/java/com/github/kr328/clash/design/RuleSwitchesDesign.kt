package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.util.ConfigRuleSnapshot
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.AppEvents
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
    rules: ConfigRuleSnapshot,
    private val uiStore: UiStore = UiStore(context),
) : Design<RuleSwitchesDesign.Request>(context) {
    sealed class Request {
        object Reload : Request()

        /** 用户拨了某条规则：只需要让内核按新的 disabledRules 重载，页面不用重画。 */
        object ApplyRuleChange : Request()
        data class ToggleScript(val enabled: Boolean) : Request()
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)
    private val host = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(24))
    }
    private var scriptEnabled = scriptOn
    private var nativeRules: ConfigRuleSnapshot = rules

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

    /** 原配置（订阅）自带的规则，脚本关掉时这一页显示的就是它们。 */
    fun updateRules(rules: ConfigRuleSnapshot) {
        nativeRules = rules
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
            renderNativeRules()
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

    /**
     * 和首页的「自定义」一样：AlertDialog 设了 message 就不再画列表，
     * 所以提示文字和勾选框要放进同一个自定义视图里。
     */
    private fun showManageDialog(names: List<String>) {
        if (names.isEmpty()) return
        val hidden = uiStore.hiddenRuleSwitches

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        content.addView(
            TextView(context).apply {
                text = getString(R.string.rule_switches_manage_hint)
                textSize = 13f
                setTextColor(context.resolveThemedColor(android.R.attr.textColorSecondary))
                setPadding(0, 0, 0, dp(4))
            },
        )
        val boxes = names.map { name ->
            CheckBox(context).apply {
                text = name
                textSize = 15f
                isChecked = name !in hidden
                minHeight = dp(44)
                setTextColor(context.resolveThemedColor(android.R.attr.textColorPrimary))
                setPadding(0, 0, 0, 0)
            }
        }
        boxes.forEach { content.addView(it) }

        AlertDialog.Builder(context)
            .setTitle(R.string.rule_switches_manage)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                uiStore.hiddenRuleSwitches = names
                    .filterIndexed { index, _ -> !boxes[index].isChecked }
                    .toSet()
                render()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 这份配置没开脚本时，这一页显示的是**它自己配置里的规则**（config.yaml 的 rules:），
     * 不是策略组名字——策略组在代理页看。
     *
     * 每条规则都能拨：关掉的规则不会进运行配置（内核跑 UnmarshalAndPatch 时按
     * script-options.json 里的 disabledRules 删掉），但 config.yaml 本身不动，
     * 所以订阅刷新不会把用户的选择冲掉。
     */
    private fun renderNativeRules() {
        host.addView(noteCard(getString(R.string.rule_switches_script_off), accent = true))

        if (nativeRules.total == 0) {
            host.addView(noteCard(getString(R.string.rule_switches_rules_empty), accent = false))
            return
        }

        val disabled = profileUuid?.let { store.disabledRules(it) } ?: emptySet()

        host.addView(
            noteCard(
                context.getString(R.string.rule_switches_rules_summary, nativeRules.total),
                accent = false,
            ),
        )

        // 订阅刷新后旧规则可能已不在列表里：只统计还看得见的，条数才对得上。
        val disabledHere = disabled.count { rule -> nativeRules.items.contains(rule) }
        if (disabledHere > 0) {
            host.addView(disabledSummary(disabledHere))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        nativeRules.items.forEachIndexed { index, rule ->
            container.addView(ruleRow(rule, enabled = rule !in disabled))
            if (index != nativeRules.items.lastIndex) {
                container.addView(divider())
            }
        }

        val more = nativeRules.total - nativeRules.items.size
        if (more > 0) {
            container.addView(divider())
            container.addView(
                TextView(context).apply {
                    text = context.getString(R.string.rule_switches_rules_more, more)
                    textSize = 12f
                    setTextColor(context.resolveThemedColor(android.R.attr.textColorSecondary))
                    setPadding(dp(16), dp(10), dp(16), dp(12))
                },
            )
        }

        host.addView(card(container, stroke = null))
    }

    /** 「已关闭 N 条」+ 一键恢复。 */
    private fun disabledSummary(count: Int): View {
        val secondary = context.resolveThemedColor(android.R.attr.textColorSecondary)
        val accent = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)

        val text = TextView(context).apply {
            this.text = context.getString(R.string.rule_switches_rules_off_count, count)
            textSize = 13f
            setTextColor(secondary)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val restore = TextView(context).apply {
            this.text = getString(R.string.rule_switches_rules_restore)
            textSize = 13f
            setTextColor(accent)
            setPadding(dp(12), dp(12), dp(16), dp(12))
        }
        restore.setOnClickListener {
            profileUuid?.let { store.clearDisabledRules(it) }
            requests.trySend(Request.ApplyRuleChange)
            render()
        }

        return card(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(restore)
            },
            stroke = null,
        )
    }

    /**
     * 一条规则：内容 + 类型，右侧是它落到哪个策略；脚本关着时右边还有一个开关。
     * 末尾的 MATCH / FINAL 是兜底规则，删掉就没有"其余全部"的去向了，所以不给开关。
     */
    private fun ruleRow(rule: String, enabled: Boolean): View {
        val primaryText = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val secondaryText = context.resolveThemedColor(android.R.attr.textColorSecondary)
        val accent = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)

        val parts = rule.split(',')
        val type = parts.firstOrNull()?.trim().orEmpty()
        val target = if (parts.size >= 2) parts.last().trim() else ""
        val payload = if (parts.size >= 3) {
            parts.subList(1, parts.size - 1).joinToString(",").trim()
        } else {
            ""
        }
        // MATCH / FINAL 这类没有匹配内容，标题就用策略名
        val title = payload.ifBlank { target.ifBlank { type } }

        val titleView = TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(primaryText)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val typeView = TextView(context).apply {
            text = type
            textSize = 11f
            setTextColor(secondaryText)
            setPadding(0, dp(2), 0, 0)
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(typeView)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            // 关掉的规则整行淡一点，一眼能看出运行时不会用它
            alpha = if (enabled) 1f else 0.55f
        }

        val targetView = TextView(context).apply {
            text = if (payload.isBlank()) "" else target
            textSize = 12f
            setTextColor(accent)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, 0, 0)
            maxWidth = dp(140)
        }

        val terminal = type.equals("MATCH", ignoreCase = true) || type.equals("FINAL", ignoreCase = true)

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            addView(labels)
            addView(targetView)

            if (terminal) {
                addView(
                    TextView(context).apply {
                        text = getString(R.string.rule_switches_rules_fixed)
                        textSize = 11f
                        setTextColor(secondaryText)
                        setPadding(dp(10), 0, dp(4), 0)
                    },
                )
                alpha = 0.75f
            } else {
                val toggle = SwitchCompat(context).apply {
                    isChecked = enabled
                    contentDescription = rule
                    minHeight = dp(44)
                    setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                        val uuid = profileUuid ?: return@setOnCheckedChangeListener
                        store.setRuleEnabled(uuid, rule, checked)
                        // 页面上直接反映状态；内核那边异步重载，不用整页重画
                        labels.alpha = if (checked) 1f else 0.55f
                        requests.trySend(Request.ApplyRuleChange)
                    }
                }
                addView(toggle)
            }
        }
    }

    private fun divider(): View =
        View(context).apply {
            setBackgroundColor(context.resolveThemedColor(R.attr.clashOutline))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            }
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

        val toggle = SwitchCompat(context).apply {
            isChecked = on
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            contentDescription = name
            minHeight = dp(48)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                if (!enabled) return@setOnCheckedChangeListener
                store.setOption(ScriptDisplayStore.GROUP_RULE, name, checked)
                requests.trySend(Request.Reload)
                // 脚本页里是同一批开关，这里改完也要让它跟着变
                AppEvents.emit(AppEvents.Event.OptionsChanged(ScriptDisplayStore.GROUP_RULE))
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

        // 扁平行 + 底部一条分隔线：列表里不再一格一框
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(View(context).apply {
                setBackgroundColor(context.resolveThemedColor(R.attr.clashOutline))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            })
        }
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
