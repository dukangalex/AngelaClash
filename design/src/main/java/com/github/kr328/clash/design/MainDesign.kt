package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenChain,
        OpenProviders,
        OpenLogs,
        OpenSettings,
        OpenApp,
        OpenEnhance,
        OpenHelp,
        OpenAbout,
        OpenScript,
        CheckUpdate,
        SetRuleMode,
        SetGlobalMode,
        SetDirectMode,
        ReloadConfig,
    }

    private data class QuickTileSpec(
        val group: String,
        val keyName: String,
        val displayTitle: String,
        val subtitle: String,
        val defaultOn: Boolean,
        val companionKey: String? = null,
    )

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    private var attentionAction: Request? = null

    private val speedPercentages = intArrayOf(
        22, 30, 26, 38, 32, 46, 36, 54, 42, 60, 48, 66, 52, 74, 58, 82
    )
    private val speedBarViews = mutableListOf<View>()

    fun onAttention() {
        attentionAction?.let(::request)
    }

    fun showHomeDetail(title: String, value: String) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(value.ifBlank { context.getString(R.string.not_selected) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showHomePolicyDetail(title: String, value: String) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(value.ifBlank { context.getString(R.string.not_selected) })
            .setNeutralButton(R.string.profiles) { _, _ -> request(Request.OpenProfiles) }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    suspend fun setHome(
        connected: String,
        safety: String,
        policy: String,
        network: String,
        attention: String?,
        attentionAction: Request?,
        safetyShort: String = safety,
        networkShort: String = network,
        coreVersion: String = "Mihomo v1.19.33",
    ) {
        withContext(Dispatchers.Main) {
            this@MainDesign.attentionAction = attentionAction
            binding.homeConnected = connected
            binding.homeSafety = safety
            binding.homeSafetyShort = safetyShort
            binding.homePolicy = policy
            binding.homeNetwork = network
            binding.homeNetworkShort = networkShort
            binding.homeAttention = attention ?: ""
            binding.showAttention = !attention.isNullOrBlank()
            binding.coreVersion = coreVersion
        }
    }

    override val root: View
        get() = binding.root

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setChainSummary(summary: String?) {
        withContext(Dispatchers.Main) {
            binding.showChain = !summary.isNullOrBlank()
            binding.chainSummary = summary ?: context.getString(R.string.chain_not_set)
        }
    }

    fun setFeatureVisibility(script: Boolean, chain: Boolean) {
        binding.showScript = script
        binding.showChain = chain
    }

    fun mountSystem(store: ScriptDisplayStore) {
        store.ensureScript()
        mountHomeQuickGrid(store)
        val host = binding.explicitHost
        host.removeAllViews()
        val titles = mapOf(
            ScriptDisplayStore.GROUP_LEAK to R.string.script_display_leak,
            ScriptDisplayStore.GROUP_CN to R.string.script_display_cn,
            ScriptDisplayStore.GROUP_STRICT to R.string.script_display_strict,
            ScriptDisplayStore.GROUP_PRIVACY to R.string.script_display_privacy,
        )
        for (section in store.systemSections()) {
            host.addView(systemCard(store, section, context.getString(titles.getValue(section.group))))
        }
    }

    private fun mountHomeQuickGrid(store: ScriptDisplayStore) {
        val container = binding.homeQuickOptions
        container.removeAllViews()
        val density = context.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()

        val specs = listOf(
            QuickTileSpec(
                group = ScriptDisplayStore.GROUP_LEAK,
                keyName = "DNS 走代理",
                displayTitle = "DNS 走代理",
                subtitle = "境外 DNS 按规则出站",
                defaultOn = true,
            ),
            QuickTileSpec(
                group = ScriptDisplayStore.GROUP_LEAK,
                keyName = "关闭 IPv6",
                displayTitle = "关闭 IPv6",
                subtitle = "阻断 IPv6 地址泄漏",
                defaultOn = true,
            ),
            QuickTileSpec(
                group = ScriptDisplayStore.GROUP_LEAK,
                keyName = "嗅探防泄漏",
                displayTitle = "嗅探防泄漏",
                subtitle = "开启域名嗅探覆盖目标",
                defaultOn = true,
            ),
            QuickTileSpec(
                group = ScriptDisplayStore.GROUP_CN,
                keyName = "中国大陆域名直连",
                displayTitle = "中国大陆直连",
                subtitle = "CN 域名与 IP 走 DIRECT",
                defaultOn = true,
                companionKey = "中国大陆 IP 直连",
            ),
            QuickTileSpec(
                group = ScriptDisplayStore.GROUP_CN,
                keyName = "局域网直连",
                displayTitle = "局域网直连",
                subtitle = "私有地址直连不送代理",
                defaultOn = true,
            ),
            QuickTileSpec(
                group = ScriptDisplayStore.GROUP_STRICT,
                keyName = "严格路由",
                displayTitle = "严格路由转发",
                subtitle = "VPN 接管全部流量防绕过",
                defaultOn = false,
            ),
        )

        val selectedBg = context.resolveThemedColor(R.attr.clashSelectedSurface)
        val normalBg = context.resolveThemedColor(com.google.android.material.R.attr.colorSurface)
        val primaryColor = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val royalStroke = ContextCompat.getColor(context, R.color.color_clash_royal)
        val outlineStroke = context.resolveThemedColor(R.attr.clashOutline)
        val textPrimary = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val textSecondary = context.resolveThemedColor(android.R.attr.textColorSecondary)

        for (rowIndex in specs.indices step 2) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                if (rowIndex > 0) lp.topMargin = dp(8)
                layoutParams = lp
            }

            for (colOffset in 0..1) {
                val idx = rowIndex + colOffset
                if (idx >= specs.size) break
                val spec = specs[idx]
                val isOn = store.optionOn(spec.group, spec.keyName, spec.defaultOn)

                val card = MaterialCardView(context).apply {
                    radius = 15f * density
                    cardElevation = 0f
                    isClickable = true
                    isFocusable = true
                    setCardBackgroundColor(if (isOn) selectedBg else normalBg)
                    strokeColor = if (isOn) royalStroke else outlineStroke
                    strokeWidth = dp(1).coerceAtLeast(1)
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    if (colOffset == 0) lp.marginEnd = dp(4) else lp.marginStart = dp(4)
                    layoutParams = lp
                }

                val inner = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(11), dp(10), dp(11), dp(10))
                }

                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val titleTv = TextView(context).apply {
                    text = spec.displayTitle
                    textSize = 12.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isOn) textPrimary else textSecondary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val statusBadge = TextView(context).apply {
                    text = if (isOn) "● 开启" else "○ 关闭"
                    textSize = 10f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isOn) primaryColor else textSecondary)
                }

                topRow.addView(titleTv)
                topRow.addView(statusBadge)

                val subTv = TextView(context).apply {
                    text = spec.subtitle
                    textSize = 10f
                    setTextColor(textSecondary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    lp.topMargin = dp(3)
                    layoutParams = lp
                }

                inner.addView(topRow)
                inner.addView(subTv)
                card.addView(inner)

                card.setOnClickListener {
                    val nextState = !store.optionOn(spec.group, spec.keyName, spec.defaultOn)
                    store.setOption(spec.group, spec.keyName, nextState)
                    spec.companionKey?.let { companion ->
                        store.setOption(spec.group, companion, nextState)
                    }
                    mountSystem(store)
                    request(Request.ReloadConfig)
                }

                rowLayout.addView(card)
            }

            container.addView(rowLayout)
        }
    }

    private fun systemCard(
        store: ScriptDisplayStore,
        section: ScriptDisplayStore.Section,
        title: String,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        val onCount = section.rows.count { it.on }
        val meta = TextView(context).apply {
            text = "$onCount/${section.rows.size}"
            setTextColor(context.resolveThemedColor(android.R.attr.textColorSecondary))
        }
        val label = TextView(context).apply {
            text = title
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(label)
            addView(meta)
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
            }
        }
        for (row in section.rows) {
            val summary = TextView(context).apply {
                text = row.summary ?: ""
                textSize = 12f
                setTextColor(context.resolveThemedColor(android.R.attr.textColorSecondary))
            }
            val titleView = TextView(context).apply {
                text = row.name
                textSize = 15f
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(titleView)
                if (!row.summary.isNullOrBlank()) addView(summary)
            }
            val toggle = SwitchCompat(context).apply {
                isChecked = row.on
                setOnCheckedChangeListener { _, checked ->
                    store.setOption(section.group, row.name, checked)
                    val fresh = store.systemSections().find { it.group == section.group }
                    val count = fresh?.rows?.count { it.on } ?: 0
                    val total = fresh?.rows?.size ?: section.rows.size
                    meta.text = "$count/$total"
                    mountHomeQuickGrid(store)
                    request(Request.ReloadConfig)
                }
            }
            body.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(texts)
                addView(toggle)
            })
        }
        return MaterialCardView(context).apply {
            radius = 20 * density
            cardElevation = 0f
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = dp(10)
            layoutParams = params
            setCardBackgroundColor(context.resolveThemedColor(R.attr.clashSurfaceVariant))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                addView(body)
            })
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashRunning = running
            renderSpeedBars(running)
        }
    }

    suspend fun setTraffic(now: Long, total: Long) {
        withContext(Dispatchers.Main) {
            binding.upload = now.trafficUpload()
            binding.download = now.trafficDownload()
            binding.forwarded = total.trafficTotal()

            // Compute live throughput bar height from combined instantaneous speed
            val downBytes = (now ushr 32) and 0xFFFFFFFFL
            val upBytes = now and 0xFFFFFFFFL
            val combined = (downBytes + upBytes).coerceAtLeast(0L)
            val pct = if (combined <= 0L) {
                20
            } else {
                val logVal = (ln(combined.toDouble() + 1.0) / ln(1024.0 * 1024.0 * 10.0) * 80.0).toInt()
                (22 + logVal).coerceIn(22, 100)
            }
            for (i in 0 until speedPercentages.size - 1) {
                speedPercentages[i] = speedPercentages[i + 1]
            }
            speedPercentages[speedPercentages.size - 1] = pct
            renderSpeedBars(binding.clashRunning)

            // Update traffic progress bar smoothly
            val totalDown = (total ushr 32) and 0xFFFFFFFFL
            val totalUp = total and 0xFFFFFFFFL
            val totalBytes = (totalDown + totalUp).coerceAtLeast(0L)
            val progressPct = if (totalBytes <= 0L) {
                24
            } else {
                val ratio = (ln(totalBytes.toDouble() + 1.0) / ln(1024.0 * 1024.0 * 1024.0 * 2.0) * 76.0).toInt()
                (20 + ratio).coerceIn(24, 96)
            }
            binding.trafficProgress.progress = progressPct
        }
    }

    private fun initSpeedBars() {
        val host = binding.speedBars
        host.removeAllViews()
        speedBarViews.clear()
        val density = context.resources.displayMetrics.density
        fun dp(n: Float) = (n * density).toInt().coerceAtLeast(1)

        for (i in speedPercentages.indices) {
            val v = View(context)
            speedBarViews.add(v)
            val lp = LinearLayout.LayoutParams(0, dp(8f), 1f)
            if (i > 0) lp.marginStart = dp(3f)
            v.layoutParams = lp
            host.addView(v)
        }
        renderSpeedBars(false)
    }

    private fun renderSpeedBars(running: Boolean) {
        if (speedBarViews.isEmpty()) return
        val density = context.resources.displayMetrics.density
        val maxH = (26f * density).toInt().coerceAtLeast(12)
        val primary = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val muted = context.resolveThemedColor(R.attr.clashOutline)

        for (i in speedBarViews.indices) {
            val view = speedBarViews[i]
            val pct = speedPercentages[i].coerceIn(16, 100)
            val h = ((maxH * pct) / 100).coerceAtLeast((4f * density).toInt())
            val lp = view.layoutParams as LinearLayout.LayoutParams
            lp.height = h
            view.layoutParams = lp

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4f * density
                val color = if (running) primary else muted
                setColor(color)
                alpha = if (running) (90 + (i * 10)).coerceAtMost(240) else 110
            }
            view.background = bg
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            val id = when (mode) {
                TunnelState.Mode.Direct -> R.id.mode_direct
                TunnelState.Mode.Global -> R.id.mode_global
                else -> R.id.mode_rule
            }
            paintMode(id)
        }
    }

    private fun buildModeLabel(
        title: String,
        subtitle: String,
        selected: Boolean,
        normalColor: Int,
        secondaryColor: Int,
    ): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append(title)
        val titleEnd = sb.length
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.02f), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(
            ForegroundColorSpan(if (selected) Color.WHITE else normalColor),
            0,
            titleEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )

        sb.append("\n")
        val subStart = sb.length
        sb.append(subtitle)
        val subEnd = sb.length
        sb.setSpan(RelativeSizeSpan(0.76f), subStart, subEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(
            ForegroundColorSpan(if (selected) Color.parseColor("#D9FFFFFF") else secondaryColor),
            subStart,
            subEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return sb
    }

    private fun paintMode(selected: Int) {
        val royalPrimary = ContextCompat.getColor(context, R.color.color_clash_royal)
        val normal = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val secondary = context.resolveThemedColor(android.R.attr.textColorSecondary)

        val specs = listOf(
            Triple(
                binding.modeRule,
                context.getString(R.string.rule_mode_title),
                context.getString(R.string.rule_mode_sub),
            ),
            Triple(
                binding.modeGlobal,
                context.getString(R.string.global_mode_title),
                context.getString(R.string.global_mode_sub),
            ),
            Triple(
                binding.modeDirect,
                context.getString(R.string.direct_mode_title),
                context.getString(R.string.direct_mode_sub),
            ),
        )

        for ((button, title, sub) in specs) {
            val on = button.id == selected
            button.backgroundTintList = ColorStateList.valueOf(
                if (on) royalPrimary else Color.TRANSPARENT
            )
            button.strokeWidth = 0
            button.text = buildModeLabel(title, sub, on, normal, secondary)
        }
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            binding.hasProviders = has
        }
    }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
            }

            AlertDialog.Builder(context)
                .setView(binding.root)
                .show()
        }
    }

    init {
        binding.self = this
        binding.pageTools = false
        binding.showScript = false
        binding.showChain = false
        binding.upload = "0 B/s"
        binding.download = "0 B/s"
        binding.forwarded = "0 B"
        binding.homeSafetyShort = context.getString(R.string.home_safety_short_on)
        binding.homeNetworkShort = context.getString(R.string.home_network_short_off)
        binding.coreVersion = "Mihomo v1.19.33"

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)
        binding.chainSummary = context.getString(R.string.chain_not_set)

        initSpeedBars()
        paintMode(R.id.mode_rule)

        binding.modeRule.setOnClickListener {
            paintMode(R.id.mode_rule)
            request(Request.SetRuleMode)
        }
        binding.modeGlobal.setOnClickListener {
            paintMode(R.id.mode_global)
            request(Request.SetGlobalMode)
        }
        binding.modeDirect.setOnClickListener {
            paintMode(R.id.mode_direct)
            request(Request.SetDirectMode)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (UiStore(context).hapticFeedback) {
                binding.bottomNav.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            when (item.itemId) {
                R.id.nav_tools -> {
                    binding.pageTools = true
                    true
                }
                R.id.nav_dashboard -> {
                    binding.pageTools = false
                    true
                }
                R.id.nav_proxy -> {
                    request(Request.OpenProxy)
                    false
                }
                R.id.nav_profiles -> {
                    request(Request.OpenProfiles)
                    false
                }
                else -> false
            }
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
