package com.github.kr328.clash.design

import android.content.Context
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.Ac
import com.github.kr328.clash.design.util.ConfigRuleSnapshot
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.service.store.ScriptDisplayStore.RuleOverride
import java.util.UUID

/**
 * 当前配置的规则与策略组。
 *
 * 和 1.4.5 之前最大的区别：**规则是一份可编辑的草稿**，不是「订阅规则 + 一串开关」。
 *
 *  - 启用脚本：脚本自己声明分流开关，这一页只负责把开关列出来。
 *  - 未启用脚本：一份跟着这份配置走的规则草稿。空草稿时整页就是订阅的规则，
 *    点「从订阅导入」把整份规则灌进草稿，之后可以增、删、改、排序。
 *    草稿有两种写法：覆盖（整份替换订阅规则）和追加（前插到订阅规则之前）。
 *
 * 为什么不是「逐条关掉」：关选要靠「规则原文」当键去和内核比较，订阅里多一个
 * 行尾注释、多一对引号，键就对不上。草稿不存键，列表本身就是结果。
 *
 * 策略组可以整组关掉：关掉的组不会进运行配置，指向它的规则改写成 PASS
 * （不删规则，删了会改变分流语义）。
 */
class RuleSwitchesDesign(
    context: Context,
    private val store: ScriptDisplayStore,
    private val profileUuid: UUID?,
    private val profileName: String?,
    scriptOn: Boolean,
    rules: ConfigRuleSnapshot,
    private val groups: List<String> = emptyList(),
    private val uiStore: UiStore = UiStore(context),
) : Design<RuleSwitchesDesign.Request>(context) {
    sealed class Request {
        object Reload : Request()

        /** 规则或策略组变了：让内核按新配置重载，页面不用重画。 */
        object ApplyRuleChange : Request()

        data class ToggleScript(val enabled: Boolean) : Request()
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)
    private val host = Ac.column(context, padTop = 4, padBottom = 24)

    private var scriptEnabled = scriptOn
    private var source: ConfigRuleSnapshot = rules
    private var sourceGroups: List<String> = groups

    private var draft: RuleOverride =
        profileUuid?.let { store.ruleOverride(it) } ?: RuleOverride.EMPTY

    /** 多选态下被选中的规则下标（指向 [draft].rules）。 */
    private var selected: MutableSet<Int> = linkedSetOf()

    /** 订阅规则一屏渲染多少条，剩下的靠「显示更多」翻。 */
    private var sourceShown = PAGE

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        binding.content.addView(
            host,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        render()
    }

    fun setScriptEnabled(enabled: Boolean) {
        scriptEnabled = enabled
        render()
    }

    /** 订阅规则和策略组换了（订阅刷新 / 切换配置）。 */
    fun updateSource(rules: ConfigRuleSnapshot, groupNames: List<String> = sourceGroups) {
        source = rules
        sourceGroups = groupNames
        sourceShown = PAGE
        if (!scriptEnabled) render()
    }

    private fun hasDraft(): Boolean = draft.rules.isNotEmpty()

    private fun render() {
        host.removeAllViews()
        selected.clear()
        host.addView(headerCard())

        if (profileUuid == null) {
            host.addView(note(getString(R.string.rule_switches_no_profile), accent = true))
            return
        }

        if (scriptEnabled) {
            renderScriptSwitches()
        } else {
            if (hasDraft()) renderDraft() else renderSource()
            renderGroups()
        }
    }

    // ---------- 头部：当前配置 + 覆写开关 ----------

    private fun headerCard(): View {
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(
                Ac.label(
                    context,
                    profileName ?: getString(R.string.rule_switches_no_profile),
                    16f,
                    bold = true,
                ),
            )
            addView(
                Ac.label(
                    context,
                    getString(
                        if (scriptEnabled) R.string.rule_override_script_on
                        else R.string.rule_override_script_off,
                    ),
                    12f,
                    secondary = true,
                ).apply { setPadding(0, dp(3), 0, 0) },
            )
        }
        val toggle = SwitchCompat(context).apply {
            isChecked = scriptEnabled
            isEnabled = profileUuid != null
            setOnCheckedChangeListener { _: android.widget.CompoundButton, checked: Boolean ->
                if (checked != scriptEnabled) requests.trySend(Request.ToggleScript(checked))
            }
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
            addView(labels)
            addView(toggle)
        }

        return gutter(Ac.card(context, body, radius = Ac.RADIUS_CARD), top = 8)
    }

    private fun gutter(view: View, top: Int = 0): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(top), dp(16), 0)
        addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    // ---------- 脚本模式：脚本声明的开关 ----------

    private fun renderScriptSwitches() {
        val rows = store.snapshot().sections.firstOrNull()?.rows.orEmpty()
        if (rows.isEmpty()) {
            host.addView(note(getString(R.string.rule_switches_empty), accent = false))
            return
        }

        host.addView(note(getString(R.string.rule_switches_summary), accent = false))

        // 用户可以把不常用的开关收进「其他设置」，但不会真的消失 ——
        // 这里只决定「放在主列表还是折叠区」，开关本身一个都不少。
        val hidden = uiStore.hiddenRuleSwitches
        val visible = rows.filter { it.name !in hidden }
        val folded = rows.filter { it.name in hidden }

        host.addView(
            Ac.section(
                context = context,
                title = getString(R.string.rule_switches_groups),
                rows = visible.map { switchRow(it.name, it.on) },
            ),
        )

        if (folded.isNotEmpty()) {
            host.addView(
                Ac.section(
                    context = context,
                    title = getString(R.string.rule_switches_other),
                    rows = folded.map { switchRow(it.name, it.on) },
                ),
            )
        }

        host.addView(
            gutter(
                Ac.action(context, getString(R.string.rule_switches_manage)) {
                    showManageDialog(rows.map { it.name })
                },
                top = 8,
            ),
        )
    }

    private fun switchRow(name: String, on: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(12), dp(12))
        addView(
            Ac.label(context, name, 14f).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        addView(
            SwitchCompat(context).apply {
                isChecked = on
                setOnCheckedChangeListener { _: android.widget.CompoundButton, checked: Boolean ->
                    store.setOption(ScriptDisplayStore.GROUP_RULE, name, checked)
                    requests.trySend(Request.ApplyRuleChange)
                }
            },
        )
    }

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
                setTextColor(Ac.secondary(context))
                setPadding(0, 0, 0, dp(4))
            },
        )
        val boxes = names.map { name ->
            CheckBox(context).apply {
                text = name
                textSize = 15f
                isChecked = name !in hidden
                minHeight = dp(44)
                setTextColor(Ac.onSurface(context))
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

    // ---------- 原配置模式：规则草稿 ----------

    /** 还没有草稿：整页就是订阅的规则，一眼看清现在到底在跑什么。 */
    private fun renderSource() {
        host.addView(
            guttedColumn(
                Ac.label(context, getString(R.string.rule_source_hint), 12f, secondary = true)
                    .apply {
                        maxLines = 3
                        ellipsize = null
                        setPadding(dp(16), dp(4), dp(16), dp(10))
                    },
                primaryButton(getString(R.string.rule_import)) { importFromSource() },
            ),
        )

        if (source.total == 0) {
            host.addView(note(getString(R.string.rule_switches_rules_empty), accent = false))
            return
        }

        val shown = source.items.take(sourceShown)
        host.addView(
            Ac.section(
                context = context,
                title = getString(R.string.rule_source_title),
                subtitle = getString(R.string.rule_source_desc, source.total),
                rows = shown.map { rule -> readOnlyRow(rule) },
            ),
        )

        val remaining = source.items.size - shown.size
        if (remaining > 0) {
            host.addView(
                gutter(
                    Ac.action(context, getString(R.string.rule_show_more, minOf(remaining, PAGE))) {
                        sourceShown += PAGE
                        render()
                    },
                    top = 6,
                ),
            )
        }
    }

    /** 已有草稿：这份草稿就是运行配置，可编辑。 */
    private fun renderDraft() {
        val modeLabel = getString(
            if (draft.mode == RuleOverride.MODE_OVERRIDE) R.string.rule_mode_override
            else R.string.rule_mode_added,
        )

        host.addView(
            Ac.header(
                context = context,
                title = getString(R.string.rule_draft_title),
                subtitle = getString(R.string.rule_draft_desc, draft.rules.size, modeLabel),
                action = Ac.action(
                    context,
                    getString(
                        if (draft.mode == RuleOverride.MODE_OVERRIDE) R.string.rule_switch_to_added
                        else R.string.rule_switch_to_override,
                    ),
                ) { toggleMode() },
            ),
        )

        host.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(dp(8), 0, dp(16), dp(8))
                addView(Ac.action(context, getString(R.string.rule_add)) { showEditDialog(null, null) })
                addView(Ac.action(context, getString(R.string.rule_import)) { importFromSource() })
                addView(Ac.action(context, getString(R.string.rule_clear)) { clearDraft() })
            },
        )

        if (selected.isNotEmpty()) host.addView(selectionBar())

        host.addView(
            Ac.section(
                context = context,
                title = "",
                rows = draft.rules.mapIndexed { index, rule -> draftRow(index, rule) },
            ),
        )

        host.addView(
            gutter(
                Ac.label(context, getString(R.string.rule_draft_footnote), 12f, secondary = true)
                    .apply {
                        maxLines = 4
                        ellipsize = null
                        setPadding(0, dp(10), 0, 0)
                    },
            ),
        )
    }

    // ---------- 策略组 ----------

    private fun renderGroups() {
        if (sourceGroups.isEmpty()) return

        val disabled = profileUuid?.let { store.disabledGroups(it) } ?: emptySet()

        host.addView(
            Ac.section(
                context = context,
                title = getString(R.string.group_switches_title),
                subtitle = getString(R.string.group_switches_desc, disabled.count { it in sourceGroups }),
                rows = sourceGroups.map { groupRow(it, it !in disabled) },
            ),
        )
        host.addView(note(getString(R.string.group_switches_hint), accent = false))
    }

    private fun groupRow(name: String, enabled: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(12), dp(12))
        addView(
            Ac.label(context, name, 14f).apply {
                alpha = if (enabled) 1f else 0.55f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        addView(
            SwitchCompat(context).apply {
                isChecked = enabled
                contentDescription = name
                minHeight = dp(44)
                setOnCheckedChangeListener { _: android.widget.CompoundButton, checked: Boolean ->
                    val uuid = profileUuid ?: return@setOnCheckedChangeListener
                    store.setGroupEnabled(uuid, name, checked)
                    requests.trySend(Request.ApplyRuleChange)
                    render()
                }
            },
        )
    }

    private fun draftRow(index: Int, rule: String): View {
        val isSelected = index in selected
        val parts = RuleParts.parse(rule)

        val topLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ac.chip(context, parts.type))
            if (parts.target.isNotEmpty()) {
                addView(
                    Ac.label(context, parts.target, 12f, secondary = true).apply {
                        setPadding(dp(6), 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                    },
                )
            }
        }

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(topLine)
            if (parts.content.isNotEmpty()) {
                addView(
                    Ac.label(context, parts.content, 13f).apply {
                        setPadding(0, dp(4), 0, 0)
                        maxLines = 2
                    },
                )
            }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (selected.isNotEmpty()) toggleSelection(index) else showEditDialog(index, rule)
            }
            setOnLongClickListener {
                toggleSelection(index)
                true
            }
            addView(labels)
            if (isSelected) {
                addView(
                    TextView(context).apply {
                        text = "✓"
                        textSize = 15f
                        setTextColor(Ac.primary(context))
                        setPadding(dp(8), 0, dp(4), 0)
                    },
                )
            }
        }
    }

    private fun readOnlyRow(rule: String): View {
        val parts = RuleParts.parse(rule)
        val topLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ac.chip(context, parts.type))
            if (parts.target.isNotEmpty()) {
                addView(
                    Ac.label(context, parts.target, 12f, secondary = true).apply {
                        setPadding(dp(6), 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                    },
                )
            }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(topLine)
            if (parts.content.isNotEmpty()) {
                addView(
                    Ac.label(context, parts.content, 13f).apply {
                        setPadding(0, dp(4), 0, 0)
                        maxLines = 2
                    },
                )
            }
        }
    }

    /** 多选态的操作条：上移 / 下移 / 删除。 */
    private fun selectionBar(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(4), dp(8), dp(4))
        addView(
            Ac.label(context, getString(R.string.rule_selected, selected.size), 13f, bold = true)
                .apply {
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
        )
        addView(Ac.action(context, getString(R.string.rule_move_up)) { moveSelection(-1) })
        addView(Ac.action(context, getString(R.string.rule_move_down)) { moveSelection(1) })
        addView(Ac.action(context, getString(R.string.rule_delete)) { deleteSelection() })
    }

    private fun guttedColumn(vararg views: View): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it) }
    }

    private fun primaryButton(label: String, onTap: () -> Unit): View = TextView(context).apply {
        text = label
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(Ac.onSurface(context))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        minimumHeight = dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            val primary = Ac.primary(context)
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 0x24))
            setStroke(dp(1), androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 0x66))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
        }
    }

    private fun note(message: String, accent: Boolean): View {
        val body = TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(if (accent) Ac.primary(context) else Ac.secondary(context))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        return gutter(Ac.card(context, body, radius = Ac.RADIUS_LIST), top = 8)
    }

    // ---------- 草稿操作 ----------

    private fun toggleSelection(index: Int) {
        if (!selected.remove(index)) selected.add(index)
        render()
    }

    private fun persist(next: RuleOverride) {
        val uuid = profileUuid ?: return
        draft = next
        store.setRuleOverride(uuid, next)
        requests.trySend(Request.ApplyRuleChange)
    }

    private fun importFromSource() {
        if (source.items.isEmpty()) return
        persist(draft.copy(enabled = true, rules = source.items.toList()))
        render()
    }

    private fun clearDraft() {
        val uuid = profileUuid ?: return
        draft = RuleOverride.EMPTY
        store.clearRuleOverride(uuid)
        selected.clear()
        requests.trySend(Request.ApplyRuleChange)
        render()
    }

    private fun toggleMode() {
        persist(
            draft.copy(
                mode = if (draft.mode == RuleOverride.MODE_OVERRIDE) RuleOverride.MODE_ADDED
                else RuleOverride.MODE_OVERRIDE,
            ),
        )
        render()
    }

    private fun deleteSelection() {
        val rules = draft.rules.filterIndexed { index, _ -> index !in selected }
        selected.clear()
        persist(draft.copy(rules = rules))
        render()
    }

    private fun moveSelection(direction: Int) {
        if (selected.isEmpty()) return
        val rules = draft.rules.toMutableList()
        val indices = if (direction < 0) selected.sorted() else selected.sortedDescending()
        var moved = false
        for (index in indices) {
            val target = index + direction
            if (target !in rules.indices) continue
            if (target in selected) continue
            rules[index] = rules[target].also { rules[target] = rules[index] }
            moved = true
        }
        if (!moved) return

        selected = selected.map { it + direction }.toMutableSet()
        persist(draft.copy(rules = rules))
        render()
    }

    // ---------- 编辑对话框 ----------

    private fun showEditDialog(index: Int?, current: String?) {
        val parsed = current?.let { RuleParts.parse(it) } ?: RuleParts("DOMAIN-SUFFIX", "", "")

        val targetOptions = (sourceGroups + BUILTIN_TARGETS).distinct()

        val typeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                RULE_TYPES,
            )
            setSelection(RULE_TYPES.indexOf(parsed.type).coerceAtLeast(0))
        }
        val contentInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(parsed.content)
            hint = getString(R.string.rule_field_content)
            setSingleLine(true)
        }
        val targetSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                targetOptions,
            )
            val position = targetOptions.indexOf(parsed.target)
            if (position >= 0) setSelection(position)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(fieldLabel(getString(R.string.rule_field_type)))
            addView(typeSpinner)
            addView(fieldLabel(getString(R.string.rule_field_content)))
            addView(contentInput)
            addView(fieldLabel(getString(R.string.rule_field_target)))
            addView(targetSpinner)
        }

        AlertDialog.Builder(context)
            .setTitle(if (index == null) R.string.rule_add else R.string.rule_edit_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val type = typeSpinner.selectedItem?.toString().orEmpty()
                val payload = contentInput.text.toString().trim()
                val target = targetSpinner.selectedItem?.toString().orEmpty()
                if (target.isEmpty()) return@setPositiveButton

                val rule = when {
                    payload.isEmpty() && type in CATCH_ALL -> "$type,$target"
                    payload.isEmpty() -> return@setPositiveButton
                    else -> "$type,$payload,$target"
                }
                val rules = draft.rules.toMutableList()
                if (index == null) rules.add(rule) else rules[index] = rule
                persist(draft.copy(enabled = true, rules = rules))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fieldLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(Ac.secondary(context))
        setPadding(0, dp(10), 0, dp(2))
    }

    /** 规则里逗号分隔的几段：类型、匹配内容、目标。 */
    data class RuleParts(val type: String, val content: String, val target: String) {
        companion object {
            fun parse(raw: String): RuleParts {
                val parts = raw.split(',')
                val type = parts.firstOrNull()?.trim().orEmpty()
                return when {
                    parts.size == 1 -> RuleParts(type, "", "")
                    parts.size == 2 -> RuleParts(type, "", parts[1].trim())
                    else -> RuleParts(
                        type,
                        parts.subList(1, parts.size - 1).joinToString(",").trim(),
                        parts.last().trim(),
                    )
                }
            }
        }
    }

    private fun getString(id: Int): String = context.getString(id)

    /** 带参数的串：%d / %s 这类格式化文案。 */
    private fun getString(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun dp(value: Int): Int = Ac.dp(context, value)

    companion object {
        private const val PAGE = 60

        /** MATCH / FINAL 只有目标，没有匹配内容。 */
        private val CATCH_ALL = setOf("MATCH", "FINAL")

        private val RULE_TYPES = listOf(
            "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX",
            "GEOIP", "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "IP-ASN",
            "GEOSITE", "RULE-SET", "PROCESS-NAME", "DST-PORT", "SRC-PORT",
            "NETWORK", "MATCH", "FINAL",
        )

        private val BUILTIN_TARGETS = listOf("DIRECT", "REJECT", "REJECT-DROP", "PASS")
    }
}
