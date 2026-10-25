package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
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

/**
 * 分流显示选项。
 *
 * 这些开关只对「启用了覆写脚本」的配置生效（见 native/config 里按 script.on 的门禁），
 * 所以页面本身按作用域分组，并把策略组结构与服务分流分开：前者决定有哪些策略组，
 * 后者决定每个服务的流量落进哪个组。
 */
class ScriptOptionsDesign(
    context: Context,
    private val store: ScriptDisplayStore,
    running: Boolean,
) : Design<ScriptOptionsDesign.Request>(context) {
    enum class Request {
        EditScript,
        ImportLink,
        Sync,
        Reload,
    }

    private val binding = DesignScriptOptionsBinding
        .inflate(context.layoutInflater, context.root, false)
    private var ruleRows: List<ScriptDisplayStore.Row> = emptyList()
    private val ruleSwitches = mutableListOf<SwitchCompat>()
    private val ruleCards = mutableListOf<MaterialCardView>()
    private val chips = mutableListOf<Chip>()
    private val expanded = mutableMapOf<String, Boolean>()
    private var suppressSwitchCallback = false

    /** A compact service chip: tap toggles, a dot shows the state. */
    private class Chip(val name: String, val card: MaterialCardView, val dot: TextView, val label: TextView)

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        styleOutlinedCard(binding.scriptEnableCard, 20)
        styleOutlinedCard(binding.sourceCard, 20)
        styleOutlinedCard(binding.editorCard, 20)
        styleOutlinedCard(binding.runtimeNotice, 16, accent = true)
        binding.runtimeNotice.visibility = if (running) View.VISIBLE else View.GONE
        showRules(store)
        showSource(store.sourceUrl(), store.syncedAt(), false)
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    fun setAllRules(enabled: Boolean) {
        if (ruleRows.isEmpty()) return
        val updates = LinkedHashMap<String, Boolean>()
        ruleRows.forEach { row ->
            if (row.name.isNotBlank()) {
                updates[row.name] = enabled
            }
        }
        store.setGroupOptions(ScriptDisplayStore.GROUP_RULE, updates)
        suppressSwitchCallback = true
        ruleSwitches.forEach { sw ->
            if (sw.isEnabled) sw.isChecked = enabled
        }
        ruleCards.forEach { card ->
            card.setStrokeColor(if (enabled) activeStroke() else inactiveStroke())
        }
        chips.forEach { chip ->
            chip.card.setStrokeColor(if (enabled) activeStroke() else inactiveStroke())
            paintChip(chip, enabled)
        }
        suppressSwitchCallback = false
        updateRuleCount()
        requests.trySend(Request.Reload)
    }

    fun showSource(url: String, syncedAt: Long, busy: Boolean) {
        binding.sourceUrl.text = if (url.isBlank()) {
            context.getString(R.string.script_source_empty)
        } else {
            url
        }
        val status = when {
            busy -> context.getString(R.string.script_syncing)
            syncedAt > 0L -> context.getString(
                R.string.script_synced_at,
                DateFormat.getMediumDateFormat(context).format(java.util.Date(syncedAt)) + " " +
                    DateFormat.getTimeFormat(context).format(java.util.Date(syncedAt)),
            )
            url.isNotBlank() -> context.getString(R.string.script_source_pending)
            else -> ""
        }
        binding.sourceStatus.text = status
        binding.sourceStatus.visibility = if (status.isBlank()) View.GONE else View.VISIBLE
        binding.importLink.isEnabled = !busy
        binding.syncScript.isEnabled = !busy
    }

    fun reload(store: ScriptDisplayStore) {
        showRules(store)
        showSource(store.sourceUrl(), store.syncedAt(), false)
    }

    private fun showRules(store: ScriptDisplayStore) {
        val snap = store.snapshot()
        ruleRows = snap.sections.firstOrNull()?.rows.orEmpty()
        binding.ruleCard.visibility = View.VISIBLE
        val emptyVis = if (ruleRows.isEmpty()) View.VISIBLE else View.GONE
        val actionVis = if (ruleRows.isEmpty()) View.GONE else View.VISIBLE
        binding.ruleEmpty.visibility = emptyVis
        binding.ruleCount.visibility = actionVis
        binding.ruleEnableAll.visibility = actionVis
        binding.ruleDisableAll.visibility = actionVis
        renderRuleSections(store)
    }

    // ---- grouping -----------------------------------------------------------

    private data class Section(val id: String, val title: String, val rows: List<ScriptDisplayStore.Row>, val chips: Boolean)

    private fun renderRuleSections(store: ScriptDisplayStore) {
        binding.ruleRows.removeAllViews()
        ruleSwitches.clear()
        ruleCards.clear()
        chips.clear()

        for (section in categorize(ruleRows)) {
            val open = expanded[section.id] ?: defaultExpanded(section.id)
            // minSdk 21: avoid Map.putIfAbsent (API 24 without desugaring).
            if (!expanded.containsKey(section.id)) {
                expanded[section.id] = open
            }
            binding.ruleRows.addView(sectionHeader(section, open))

            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (open) View.VISIBLE else View.GONE
            }
            if (section.chips) {
                body.addView(chipGrid(store, section.rows))
            } else {
                section.rows.forEachIndexed { index, row ->
                    body.addView(ruleRow(store, row, index))
                }
            }
            binding.ruleRows.addView(body)
        }
        updateRuleCount()
    }

    /**
     * 服务分流默认展开（用户最常调），其余默认收起：一屏就能看到全部类别。
     */
    private fun defaultExpanded(id: String): Boolean = id == CATEGORY_SERVICE

    private fun categorize(rows: List<ScriptDisplayStore.Row>): List<Section> {
        val buckets = linkedMapOf(
            CATEGORY_SERVICE to mutableListOf<ScriptDisplayStore.Row>(),
            CATEGORY_STRUCTURE to mutableListOf<ScriptDisplayStore.Row>(),
            CATEGORY_FILTER to mutableListOf<ScriptDisplayStore.Row>(),
            CATEGORY_NETWORK to mutableListOf<ScriptDisplayStore.Row>(),
        )
        for (row in rows) {
            buckets.getValue(categoryOf(row.name)).add(row)
        }
        return listOf(
            Section(CATEGORY_SERVICE, context.getString(R.string.script_group_service), buckets.getValue(CATEGORY_SERVICE), true),
            Section(CATEGORY_STRUCTURE, context.getString(R.string.script_group_structure), buckets.getValue(CATEGORY_STRUCTURE), false),
            Section(CATEGORY_FILTER, context.getString(R.string.script_group_filter), buckets.getValue(CATEGORY_FILTER), false),
            Section(CATEGORY_NETWORK, context.getString(R.string.script_group_network), buckets.getValue(CATEGORY_NETWORK), false),
        ).filter { it.rows.isNotEmpty() }
    }

    private fun categoryOf(name: String): String = when {
        name in STRUCTURE_OPTIONS -> CATEGORY_STRUCTURE
        name in FILTER_OPTIONS -> CATEGORY_FILTER
        name in NETWORK_OPTIONS || name.startsWith("屏蔽") || name.startsWith("强制") ||
            name.contains("QUIC") || name.contains("WebRTC") || name.startsWith("代理IPV") ->
            CATEGORY_NETWORK
        else -> CATEGORY_SERVICE
    }

    private fun sectionHeader(section: Section, open: Boolean): View {
        val on = section.rows.count { it.on }
        val title = TextView(context).apply {
            text = section.title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.resolveThemedColor(android.R.attr.textColorSecondary))
        }
        val count = TextView(context).apply {
            text = context.getString(R.string.script_group_count, on, section.rows.size)
            textSize = 11f
            setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(8), 0, 0, 0)
        }
        val chevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_chevron_right)
            setColorFilter(context.resolveThemedColor(android.R.attr.textColorSecondary))
            rotation = if (open) 90f else 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(6))
            isClickable = true
            isFocusable = true
            contentDescription = section.title
            addView(title)
            addView(count)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(chevron)
            setOnClickListener {
                val nowOpen = !(expanded[section.id] ?: defaultExpanded(section.id))
                expanded[section.id] = nowOpen
                val body = ruleRowsBody(section.id)
                body?.visibility = if (nowOpen) View.VISIBLE else View.GONE
                chevron.animate().rotation(if (nowOpen) 90f else 0f).setDuration(120).start()
            }
        }.also { it.tag = bodyTag(section.id) }
    }

    private fun bodyTag(id: String) = "body-$id"

    /** The body view is the sibling right after the header. */
    private fun ruleRowsBody(id: String): View? {
        val parent = binding.ruleRows
        for (index in 0 until parent.childCount) {
            if (parent.getChildAt(index).tag == bodyTag(id)) {
                return parent.getChildAt(index + 1)
            }
        }
        return null
    }

    private fun chipGrid(store: ScriptDisplayStore, rows: List<ScriptDisplayStore.Row>): View {
        val grid = GridLayout(context).apply {
            columnCount = 2
            useDefaultMargins = false
        }
        val margin = dp(3)
        rows.forEach { row ->
            val chip = chip(store, row)
            val params = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(margin, margin, margin, margin)
            }
            grid.addView(chip.card, params)
        }
        return grid
    }

    private fun chip(store: ScriptDisplayStore, row: ScriptDisplayStore.Row): Chip {
        val name = row.name
        val dot = TextView(context).apply {
            textSize = 11f
            setTextColor(if (row.on) primaryColor() else secondaryColor())
        }
        val label = TextView(context).apply {
            text = name
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(6), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(11), dp(10), dp(11))
            addView(dot)
            addView(label)
        }
        val card = MaterialCardView(context).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setCardBackgroundColor(surfaceColor())
            setStrokeColor(if (row.on) activeStroke() else inactiveStroke())
            isClickable = true
            isFocusable = true
            addView(content)
        }
        val holder = Chip(name, card, dot, label)
        paintChip(holder, row.on)
        card.setOnClickListener {
            val next = !(it.tag as? Boolean ?: row.on)
            store.setOption(ScriptDisplayStore.GROUP_RULE, name, next)
            paintChip(holder, next)
            updateRuleCount()
            requests.trySend(Request.Reload)
        }
        card.tag = row.on
        return holder
    }

    private fun paintChip(chip: Chip, on: Boolean) {
        chip.dot.text = if (on) "●" else "○"
        chip.dot.setTextColor(if (on) primaryColor() else secondaryColor())
        chip.label.setTypeface(chip.label.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        chip.card.setStrokeColor(if (on) activeStroke() else inactiveStroke())
        chip.card.tag = on
        chip.card.contentDescription = context.getString(
            if (on) R.string.script_group_chip_on else R.string.script_group_chip_off,
            chip.name,
        )
    }

    // ---- rows ---------------------------------------------------------------

    private fun ruleRow(store: ScriptDisplayStore, row: ScriptDisplayStore.Row, index: Int): View {
        val displayName = row.name.ifBlank {
            context.getString(R.string.script_option_unnamed, index + 1)
        }
        val primaryText = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val secondaryText = context.resolveThemedColor(android.R.attr.textColorSecondary)

        val title = TextView(context).apply {
            text = displayName
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText)
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)

            row.summary?.takeIf { it.isNotBlank() }?.let { summaryText ->
                addView(TextView(context).apply {
                    text = summaryText
                    textSize = 12f
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
        lateinit var cardView: MaterialCardView
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
                if (suppressSwitchCallback) return@setOnCheckedChangeListener
                cardView.setStrokeColor(if (enabled) activeStroke() else inactiveStroke())
                store.setOption(ScriptDisplayStore.GROUP_RULE, row.name, enabled)
                updateRuleCount()
                requests.trySend(Request.Reload)
            }
        }
        ruleSwitches += toggle

        val item = FrameLayout(context).apply {
            minimumHeight = dp(60)
            isClickable = true
            isFocusable = true
            addView(labels, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL,
            ).apply {
                marginStart = dp(14)
                marginEnd = dp(72)
                topMargin = dp(8)
                bottomMargin = dp(8)
            })
            addView(toggle, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ).apply { marginEnd = dp(10) })
            setOnClickListener {
                if (toggle.isEnabled) toggle.isChecked = !toggle.isChecked
            }
        }

        cardView = MaterialCardView(context).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(surfaceColor())
            strokeWidth = dp(1)
            setStrokeColor(if (row.on) activeStroke() else inactiveStroke())
            addView(item, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        ruleCards += cardView
        return cardView
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

    private fun surfaceColor(): Int = context.resolveThemedColor(R.attr.clashSurfaceVariant)

    private fun primaryColor(): Int =
        context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)

    private fun secondaryColor(): Int =
        context.resolveThemedColor(android.R.attr.textColorSecondary)

    private fun activeStroke(): Int = ColorUtils.setAlphaComponent(primaryColor(), 140)

    private fun inactiveStroke(): Int = context.resolveThemedColor(R.attr.clashOutline)

    private fun updateRuleCount() {
        val on = ruleSwitches.count { it.isChecked } + chips.count { it.card.tag == true }
        binding.ruleCount.text = context.getString(
            R.string.script_rule_count_format,
            on,
            ruleRows.size,
        )
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val CATEGORY_SERVICE = "service"
        private const val CATEGORY_STRUCTURE = "structure"
        private const val CATEGORY_FILTER = "filter"
        private const val CATEGORY_NETWORK = "network"

        /** 决定"有哪些策略组"的结构性开关。 */
        private val STRUCTURE_OPTIONS = setOf(
            "手动选择", "自动选择", "负载均衡", "故障转移", "极简模式",
            "生成地区自动选择组", "隐藏地区手动选择组", "生成倍率组", "分流组添加所有节点",
        )

        /** 节点过滤：只影响组成员，不新建策略组。 */
        private val FILTER_OPTIONS = setOf("过滤低倍率节点", "过滤高倍率节点", "过滤非地区节点")

        /** 纯规则级防护：不改策略组，对没有脚本的配置也生效。 */
        private val NETWORK_OPTIONS = setOf(
            "屏蔽国外QUIC", "屏蔽WebRTC", "强制TUN", "代理IPV4优先", "代理IPV6优先", "链式代理",
        )
    }
}
