package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
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
import com.github.kr328.clash.design.ui.AppEvents
import com.github.kr328.clash.design.model.HomeBlock
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
        OpenNetworkSettings,
        OpenAccessControl,
        OpenMetaFeature,
        OpenRuleSwitches,
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
        RunSelfCheck,
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
    private var scriptStoreRef: ScriptDisplayStore? = null

    private var peakDownRaw: Long = 0L
    private var peakUpRaw: Long = 0L

    // 面积图的数据在 SpeedChartView 内部维护，这里不再存柱子高度。

    /**
     * 首页区块显隐。用户勾掉的区块记在 UiStore.homeHiddenBlocks，
     * 存 key 而不是下标，以后新增区块对老用户默认可见。
     */
    private fun applyHomeBlocks() {
        val store = UiStore(context)
        val hidden = store.homeHiddenBlocks
        val hosts = mapOf(
            HomeBlock.SPEED to binding.blockSpeed,
            HomeBlock.PROFILE to binding.blockProfile,
            HomeBlock.PROXY to binding.blockProxy,
            HomeBlock.DIAG to binding.blockDiag,
            HomeBlock.META to binding.blockMeta,
            HomeBlock.QUICK to binding.blockQuick,
        )
        hosts.forEach { (block, view) ->
            view.visibility = if (block.key in hidden) View.GONE else View.VISIBLE
        }
    }

    /** 顶部那个滑动条图标：勾选想留在首页的区块。 */
    fun onCustomizeHome() {
        val store = UiStore(context)
        val hidden = store.homeHiddenBlocks
        val blocks = HomeBlock.values()
        val labels = blocks.map { context.getString(it.titleRes) }.toTypedArray()
        // 勾选表示「显示」，所以初始值 = 没被隐藏
        val checked = blocks.map { it.key !in hidden }.toBooleanArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.home_customize_title)
            .setMessage(R.string.home_customize_hint)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                store.homeHiddenBlocks = blocks
                    .filterIndexed { index, _ -> !checked[index] }
                    .map { it.key }
                    .toSet()
                applyHomeBlocks()
            }
            .setNeutralButton(R.string.home_block_restore) { _, _ ->
                store.homeHiddenBlocks = emptySet()
                applyHomeBlocks()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun onAttention() {
        attentionAction?.let(::request)
    }

    fun showTrafficConsole() {
        val peakDownStr = (peakDownRaw shl 32).trafficDownload()
        val peakUpStr = peakUpRaw.trafficUpload()
        val statusText = if (binding.clashRunning) "运行中 (实时采集中)" else "未连接 (待机)"
        val message = buildString {
            appendLine("• 隧道状态：$statusText")
            appendLine("• 当前下行：${binding.download}")
            appendLine("• 当前上行：${binding.upload}")
            appendLine("• 会话峰值：↓ $peakDownStr  /  ↑ $peakUpStr")
            appendLine("• 累计转发：${binding.forwarded}")
            appendLine("• 统计栈：${binding.profileStackSummary}")
        }.trim()

        AlertDialog.Builder(context)
            .setTitle("实时吞吐与流量控制台")
            .setMessage(message)
            .setPositiveButton("查看实时日志") { _, _ ->
                request(Request.OpenLogs)
            }
            .setNeutralButton("重置峰值与波形") { _, _ ->
                peakDownRaw = 0L
                peakUpRaw = 0L
                binding.speedBars.reset()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun onCoreEngineClick() {
        val items = arrayOf(
            "立即热重载内核配置 (Hot Reload)",
            "内核与 Meta 特性设置 (嗅探 / 测速 / Geo库)",
            "全局覆写与 DNS 配置 (Override & DNS)",
            "外部资源同步与管理 (Providers)",
        )
        AlertDialog.Builder(context)
            .setTitle("${binding.coreVersion} · 内核控制台")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> request(Request.ReloadConfig)
                    1 -> request(Request.OpenMetaFeature)
                    2 -> request(Request.OpenSettings)
                    3 -> request(Request.OpenProviders)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun onTunNetworkClick() {
        val store = scriptStoreRef ?: ScriptDisplayStore(context)
        val lanOn = store.optionOn(ScriptDisplayStore.GROUP_CN, "局域网直连", true)
        val strictOn = store.optionOn(ScriptDisplayStore.GROUP_STRICT, "严格路由", false)
        val items = arrayOf(
            "打开 VPN 与网络设置 (DNS劫持 / IPv6 / 系统代理)",
            "配置分应用代理名单 (Access Control)",
            "${if (lanOn) "关闭" else "开启"}「局域网直连」 (当前: ${if (lanOn) "已开启" else "已关闭"})",
            "${if (strictOn) "关闭" else "开启"}「严格路由转发」 (当前: ${if (strictOn) "已开启" else "已关闭"})",
        )
        AlertDialog.Builder(context)
            .setTitle("内网与虚拟网卡 (TUN) 控制")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> request(Request.OpenNetworkSettings)
                    1 -> request(Request.OpenAccessControl)
                    2 -> {
                        store.setOption(ScriptDisplayStore.GROUP_CN, "局域网直连", !lanOn)
                        mountSystem(store)
                        request(Request.ReloadConfig)
                    }
                    3 -> {
                        store.setOption(ScriptDisplayStore.GROUP_STRICT, "严格路由", !strictOn)
                        mountSystem(store)
                        request(Request.ReloadConfig)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun onConnectionDiagClick() {
        val running = binding.clashRunning
        val msg = buildString {
            appendLine("• 当前状态：${binding.homeConnected}")
            appendLine("• 链路详情：${binding.homeConnectedSub}")
            appendLine("• 当前配置：${binding.profileName ?: context.getString(R.string.not_selected)}")
            appendLine("• 链式代理：${binding.chainSummary}")
            appendLine("• 内核版本：${binding.coreVersion}")
        }.trim()

        AlertDialog.Builder(context)
            .setTitle("连接状态与隧道控制")
            .setMessage(msg)
            .setPositiveButton(if (running) "停止 VPN 服务" else "立即启动服务") { _, _ ->
                request(Request.ToggleStatus)
            }
            .setNeutralButton("配置链式代理") { _, _ ->
                request(Request.OpenChain)
            }
            .setNegativeButton("热重载内核") { _, _ ->
                request(Request.ReloadConfig)
            }
            .show()
    }

    fun onSafetyDiagClick() {
        val store = scriptStoreRef ?: ScriptDisplayStore(context)
        val keys = arrayOf("DNS 走代理", "禁止系统 DNS", "关闭 IPv6", "嗅探防泄漏")
        val labels = arrayOf(
            "DNS 走代理 (境外 DNS 按规则出站)",
            "禁止系统 DNS (阻断系统明文 DNS 抢答)",
            "关闭 IPv6 (阻断 IPv6 旁路泄漏)",
            "嗅探防泄漏 (开启域名嗅探校正目标)",
        )
        val checked = BooleanArray(keys.size) { i ->
            store.optionOn(ScriptDisplayStore.GROUP_LEAK, keys[i], true)
        }

        AlertDialog.Builder(context)
            .setTitle("安全防护实时开关 (${binding.homeSafetyShort})")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("应用并重载") { _, _ ->
                for (i in keys.indices) {
                    store.setOption(ScriptDisplayStore.GROUP_LEAK, keys[i], checked[i])
                }
                mountSystem(store)
                request(Request.ReloadConfig)
            }
            .setNeutralButton("一键全开防护") { _, _ ->
                for (key in keys) {
                    store.setOption(ScriptDisplayStore.GROUP_LEAK, key, true)
                }
                mountSystem(store)
                request(Request.ReloadConfig)
            }
            .setNegativeButton("覆写脚本页") { _, _ ->
                request(Request.OpenScript)
            }
            .show()
    }

    fun onPolicyDiagClick() {
        val items = arrayOf(
            "切换出站模式：规则分流 (Rule Mode)",
            "切换出站模式：全局代理 (Global Mode)",
            "切换出站模式：直连模式 (Direct Mode)",
            "进入代理策略组选路 (${binding.proxyHeadline})",
            "打开分流规则开关 (Rule Switches)",
            "管理或切换订阅配置 (Profiles)",
        )
        AlertDialog.Builder(context)
            .setTitle("当前策略与选路控制")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        paintMode(R.id.mode_rule)
                        request(Request.SetRuleMode)
                    }
                    1 -> {
                        paintMode(R.id.mode_global)
                        request(Request.SetGlobalMode)
                    }
                    2 -> {
                        paintMode(R.id.mode_direct)
                        request(Request.SetDirectMode)
                    }
                    3 -> request(Request.OpenProxy)
                    4 -> request(Request.OpenRuleSwitches)
                    5 -> request(Request.OpenProfiles)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun onNetworkDiagClick() {
        val items = arrayOf(
            "查看实时连接日志 (Logcat)",
            "打开实时吞吐与峰值统计面板",
            "打开 VPN 与虚拟网卡设置",
            "配置分应用代理名单 (Access Control)",
            "打开增强工具 (商店修复 / 电池优化 / 高优通知)",
        )
        AlertDialog.Builder(context)
            .setTitle("网络转发与诊断工具 (${binding.homeNetworkShort})")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> request(Request.OpenLogs)
                    1 -> showTrafficConsole()
                    2 -> request(Request.OpenNetworkSettings)
                    3 -> request(Request.OpenAccessControl)
                    4 -> request(Request.OpenEnhance)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    suspend fun showSelfCheckReport(title: String, report: String) {
        withContext(Dispatchers.Main) {
            AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(report)
                .setPositiveButton("代理策略组测速") { _, _ ->
                    request(Request.OpenProxy)
                }
                .setNeutralButton("实时日志") { _, _ ->
                    request(Request.OpenLogs)
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        }
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
        coreSubtitle: String = "核心 3f27ef39 · 零拷贝转发",
        tunHeadline: String = "TUN 混合栈 · IPv6 阻断",
        tunSubtitle: String = "DNS 劫持 · 局域网直连",
        profileStackSummary: String = "TUN 混合栈 · 实时统计",
        proxyBadge: String = "已就绪",
        proxyHeadline: String = policy,
        proxySubtitle: String = "点击进入代理策略组选路与延迟测速",
        chainBadge: String = "Fail-Closed",
        chainSubtitle: String = "本机 → 入口节点 → 落地节点 → 目标网站",
        connectedSub: String = "TUN 隧道 · 局域网直连",
        safetySub: String = "DNS 受控 · IPv6 已阻断",
        policySub: String = "跨重启记忆 · 点击切换",
        networkSub: String = "点击查看实时日志与网络设置",
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
            binding.coreSubtitle = coreSubtitle
            binding.tunHeadline = tunHeadline
            binding.tunSubtitle = tunSubtitle
            binding.profileStackSummary = profileStackSummary
            binding.proxyBadge = proxyBadge
            binding.proxyHeadline = proxyHeadline
            binding.proxySubtitle = proxySubtitle
            binding.chainBadge = chainBadge
            binding.chainSubtitle = chainSubtitle
            binding.homeConnectedSub = connectedSub
            binding.homeSafetySub = safetySub
            binding.homePolicySub = policySub
            binding.homeNetworkSub = networkSub
        }
    }

    override val root: View
        get() = binding.root

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    /**
     * 首页副标题上那枚「脚本 ON / 无脚本」徽标：让用户一眼看出当前配置会不会被覆写脚本改写。
     * null = 没有选中配置，不显示。
     */
    suspend fun setProfileScript(scriptOn: Boolean?) {
        withContext(Dispatchers.Main) {
            binding.profileScriptBadge = when (scriptOn) {
                null -> ""
                true -> context.getString(R.string.dashboard_script_on)
                false -> context.getString(R.string.dashboard_script_off)
            }
        }
    }

    suspend fun setChainSummary(summary: String?) {
        withContext(Dispatchers.Main) {
            val hasChain = !summary.isNullOrBlank()
            binding.chainSummary = summary ?: context.getString(R.string.chain_not_set)
            binding.chainBadge = if (hasChain) "已组链 · Fail-Closed" else "点击组链"
            binding.chainSubtitle = if (hasChain) {
                "双跳事务强绑定已生效 · 链路异常直接阻断不回退 DIRECT"
            } else {
                context.getString(R.string.chain_card_subtitle)
            }
        }
    }

    fun setFeatureVisibility(script: Boolean, chain: Boolean) {
        binding.showScript = script
        binding.showChain = chain
    }

    fun mountSystem(store: ScriptDisplayStore) {
        scriptStoreRef = store
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
        val surfaceVariantBg = context.resolveThemedColor(R.attr.clashSurfaceVariant)
        val isDarkSurface =
            (Color.red(surfaceVariantBg) + Color.green(surfaceVariantBg) + Color.blue(surfaceVariantBg)) < 384
        val offChipBg = if (isDarkSurface) Color.parseColor("#FF1B263B") else Color.parseColor("#FFF1F5F9")
        val primaryColor = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val textPrimary = if (isDarkSurface) Color.WHITE else Color.parseColor("#FF0F172A")
        val textSecondary = if (isDarkSurface) Color.parseColor("#FF94A3B8") else Color.parseColor("#FF64748B")

        // 6 个开关做成一行横滑 chip：只保留「名字 + 一个点」，
        // 名字本身就是状态，不再重复写「● 开启 / ○ 已关闭」。
        for (spec in specs) {
            val isOn = store.optionOn(spec.group, spec.keyName, spec.defaultOn)

            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f * density
                    setColor(if (isOn) selectedBg else offChipBg)
                }
                setPadding(dp(12), dp(9), dp(12), dp(9))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.marginEnd = dp(8)
                layoutParams = lp
            }

            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isOn) primaryColor else textSecondary)
                }
                val lp = LinearLayout.LayoutParams(dp(6), dp(6))
                lp.marginEnd = dp(7)
                layoutParams = lp
                alpha = if (isOn) 1f else 0.5f
            }

            val label = TextView(context).apply {
                text = spec.displayTitle
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isOn) textPrimary else textSecondary)
                maxLines = 1
            }

            chip.addView(dot)
            chip.addView(label)

            chip.setOnClickListener {
                if (UiStore(context).hapticFeedback) {
                    chip.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                val nextState = !store.optionOn(spec.group, spec.keyName, spec.defaultOn)
                store.setOption(spec.group, spec.keyName, nextState)
                spec.companionKey?.let { companion ->
                    store.setOption(spec.group, companion, nextState)
                }
                mountSystem(store)
                request(Request.ReloadConfig)
                // 脚本页里有一模一样的开关，这里改完要让那边跟着变
                AppEvents.emit(AppEvents.Event.OptionsChanged(spec.group))
            }

            container.addView(chip)
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
            radius = 22 * density
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
            val totalStr = total.trafficTotal()
            binding.forwarded = totalStr
            binding.homeNetworkSub = context.getString(R.string.dashboard_total, totalStr) + " · ↓ " + binding.download

            // Compute live throughput bar height from combined instantaneous speed
            val downBytes = (now ushr 32) and 0xFFFFFFFFL
            val upBytes = now and 0xFFFFFFFFL
            if (downBytes > peakDownRaw) peakDownRaw = downBytes
            if (upBytes > peakUpRaw) peakUpRaw = upBytes

            // 面积图要的是上下行各自的曲线，不是合成值：
            // 用对数归一化，否则 10 MB/s 和 100 KB/s 在图上都贴着顶。
            binding.speedBars.push(
                normalizeSpeed(downBytes),
                normalizeSpeed(upBytes),
                binding.clashRunning,
            )

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

    /** 对数归一化：0 B/s -> 0，10 MB/s 以上 -> 1。比线性好看得多，也不会一直贴顶。 */
    private fun normalizeSpeed(bytes: Long): Float {
        if (bytes <= 0L) return 0f
        val v = ln(bytes.toDouble() + 1.0) / ln(1024.0 * 1024.0 * 10.0)
        return v.toFloat().coerceIn(0f, 1f)
    }

    private fun initSpeedBars() {
        binding.speedBars.setRunning(false)
    }

    private fun renderSpeedBars(running: Boolean) {
        // 保留方法名（setClashRunning 还在调），只切运行状态，不写采样点，
        // 真正的曲线由 setTraffic() 每秒 push 一次上下行。
        binding.speedBars.setRunning(running)
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

    private fun paintMode(selected: Int) {
        val royalPrimary = ContextCompat.getColor(context, R.color.color_clash_royal)
        val normal = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)

        // BettBox 的分段控件只有文字，没有「智能规则匹配」这类副标题 —— 副标题是噪音。
        val specs = listOf(
            binding.modeRule to context.getString(R.string.rule_mode_title),
            binding.modeGlobal to context.getString(R.string.global_mode_title),
            binding.modeDirect to context.getString(R.string.direct_mode_title),
        )

        for ((button, title) in specs) {
            val on = button.id == selected
            button.backgroundTintList = ColorStateList.valueOf(
                if (on) royalPrimary else Color.TRANSPARENT
            )
            button.strokeWidth = 0
            button.setTextColor(if (on) Color.WHITE else normal)
            button.text = title
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
        binding.showChain = true
        binding.upload = "0 B/s"
        binding.download = "0 B/s"
        binding.forwarded = "0 B"
        binding.homeSafetyShort = context.getString(R.string.home_safety_short_on)
        binding.homeNetworkShort = context.getString(R.string.home_network_short_off)
        binding.coreVersion = "Mihomo v1.19.33"
        binding.coreSubtitle = "核心 3f27ef39 · 零拷贝转发"
        binding.tunHeadline = "TUN 混合栈 · IPv6 阻断"
        binding.tunSubtitle = "DNS 劫持 · 局域网直连"
        binding.profileStackSummary = "TUN 混合栈 · 实时统计"
        binding.proxyBadge = context.getString(R.string.proxy_ready_badge)
        binding.proxyHeadline = context.getString(R.string.not_selected)
        binding.proxySubtitle = context.getString(R.string.proxy_card_subtitle)
        binding.chainBadge = "点击组链"
        binding.chainSummary = context.getString(R.string.chain_not_set)
        binding.chainSubtitle = context.getString(R.string.chain_card_subtitle)
        binding.homeConnectedSub = context.getString(R.string.home_connected_sub)
        binding.homeSafetySub = context.getString(R.string.home_safety_sub)
        binding.homePolicySub = context.getString(R.string.home_policy_sub)
        binding.homeNetworkSub = "累计 0 B · 点击查看工具"

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)

        initSpeedBars()
        paintMode(R.id.mode_rule)
        applyHomeBlocks()

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
