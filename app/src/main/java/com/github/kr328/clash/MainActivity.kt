package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ChainStore
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity<MainDesign>() {
    override fun allowPullBack(): Boolean = false

    override suspend fun main() {
        val design = MainDesign(this)
        val options = ScriptDisplayStore(this)
        design.mountSystem(options)
        RestartReceiver.sync(this)
        setContentDesign(design)
        if (!launched) {
            launched = true
            if (uiStore.autoConnect && !clashRunning) {
                design.startClash()
            }
            if (uiStore.autoCheckUpdate) {
                AppUpdate(this).check(silent = true)
            }
        }

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenChain ->
                            startActivity(ChainActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs ->
                            startActivity(LogcatActivity::class.intent)
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenNetworkSettings ->
                            startActivity(NetworkSettingsActivity::class.intent)
                        MainDesign.Request.OpenAccessControl ->
                            startActivity(AccessControlActivity::class.intent)
                        MainDesign.Request.OpenMetaFeature ->
                            startActivity(MetaFeatureSettingsActivity::class.intent)
                        MainDesign.Request.OpenRuleSwitches ->
                            startActivity(RuleSwitchesActivity::class.intent)
                        MainDesign.Request.OpenApp ->
                            startActivity(AppSettingsActivity::class.intent)
                        MainDesign.Request.OpenEnhance ->
                            startActivity(EnhanceToolsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                        MainDesign.Request.OpenScript ->
                            startActivity(ScriptOptionsActivity::class.intent)
                        MainDesign.Request.CheckUpdate ->
                            AppUpdate(this@MainActivity).check()
                        MainDesign.Request.SetRuleMode ->
                            patchMode(TunnelState.Mode.Rule)
                        MainDesign.Request.SetGlobalMode ->
                            patchMode(TunnelState.Mode.Global)
                        MainDesign.Request.SetDirectMode ->
                            patchMode(TunnelState.Mode.Direct)
                        MainDesign.Request.ReloadConfig -> {
                            reloadActive()
                            design.fetch()
                        }
                        MainDesign.Request.RunSelfCheck -> {
                            launch {
                                design.runSelfCheck()
                            }
                        }
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setHasProviders(providers.isNotEmpty())

        val active = withProfile { queryActive() }
        setProfileName(active?.name)
        val summary = active?.let { ChainStore(this@MainActivity).summary(it.uuid) }
        setChainSummary(summary)
        // Chaining is a built-in capability, so keep its entry visible before the first binding exists.
        val scriptStore = ScriptDisplayStore(this@MainActivity)
        val serviceStore = ServiceStore(this@MainActivity)
        setFeatureVisibility(scriptStore.scriptEnabled(), true)
        mountSystem(scriptStore)
        val shown = shownMode(state.mode)
        setMode(shown)

        val rawCore = withContext(Dispatchers.IO) {
            Bridge.nativeCoreVersion().replace("_", "-")
        }
        val coreHeadline = "Mihomo v1.19.33"
        val coreSub = "$rawCore · 外部资源 ${providers.size} 个"

        val groupNames = if (clashRunning) {
            runCatching { withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) } }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val primaryGroup = if (clashRunning && groupNames.isNotEmpty()) {
            runCatching { withClash { queryProxyGroup(groupNames.first(), uiStore.proxySort) } }
                .getOrNull()
        } else {
            null
        }

        val vpnHints = scriptStore.vpnHints()
        val ipv6Blocked = vpnHints.blockIpv6 && !serviceStore.allowIpv6
        val dnsHijacked = vpnHints.hijackDns || serviceStore.dnsHijacking
        val lanBypass = scriptStore.optionOn(ScriptDisplayStore.GROUP_CN, "局域网直连", true) &&
            serviceStore.bypassPrivateNetwork

        val tunHeadline = "TUN 混合栈 · " + if (ipv6Blocked) "IPv6 阻断" else "IPv6 放行"
        val tunSubtitle = (if (dnsHijacked) "DNS 劫持" else "DNS 直通") + " · " +
            (if (lanBypass) "局域网直连" else "全局接管")

        val profileTypeStr = when (active?.type) {
            Profile.Type.Url -> "远程订阅"
            Profile.Type.File -> "本地配置"
            Profile.Type.External -> "外部配置"
            null -> "未选配置"
        }
        val profileStackSummary = "$profileTypeStr · " + if (dnsHijacked) "DNS 劫持已开" else "TUN 实时统计"

        val policyStr = homePolicy(active?.name, shown)
        val proxyBadge = when {
            clashRunning && groupNames.isNotEmpty() -> "${groupNames.size} 组运行中"
            clashRunning -> getString(DesignR.string.running)
            else -> getString(DesignR.string.proxy_ready_badge)
        }
        val proxyHeadline = if (clashRunning && primaryGroup != null && groupNames.isNotEmpty()) {
            "${groupNames.first()} · ${primaryGroup.now}"
        } else {
            policyStr
        }
        val proxySubtitle = if (clashRunning && primaryGroup != null) {
            "共 ${groupNames.size} 个策略组 (${primaryGroup.proxies.size} 节点) · 点击选路或测速"
        } else {
            getString(DesignR.string.proxy_card_subtitle)
        }

        val hasChain = !summary.isNullOrBlank()
        val chainBadge = if (hasChain) "已组链 · Fail-Closed" else "点击组链"
        val chainSub = if (hasChain) {
            "双跳事务强绑定已生效 · 链路异常直接阻断不回退 DIRECT"
        } else {
            getString(DesignR.string.chain_card_subtitle)
        }

        val connectedSub = when {
            !clashRunning -> "服务未启动 · 点击开启连接"
            hasChain -> "双跳链式 · Fail-Closed"
            else -> "TUN 隧道 · " + if (lanBypass) "局域网直连" else "全局接管"
        }

        val dnsGuardOn = scriptStore.optionOn(ScriptDisplayStore.GROUP_LEAK, "DNS 走代理", true)
        val ipv6GuardOff = scriptStore.optionOn(ScriptDisplayStore.GROUP_LEAK, "关闭 IPv6", true)
        val safetySub = "DNS ${if (dnsGuardOn) "受控" else "直通"} · IPv6 ${if (ipv6GuardOff) "已阻断" else "已放行"}"

        val policySub = if (clashRunning && primaryGroup != null) {
            "当前节点: ${primaryGroup.now}"
        } else {
            "跨重启记忆 · 点击切换"
        }

        val networkSub = if (clashRunning) {
            "转发正常 · 点击打开日志/工具"
        } else {
            "未开启转发 · 点击配置网络"
        }

        setHome(
            connected = getString(if (clashRunning) DesignR.string.home_connected_on else DesignR.string.home_connected_off),
            safety = homeSafety(shown),
            policy = policyStr,
            network = getString(if (clashRunning) DesignR.string.home_network_on else DesignR.string.home_network_off),
            attention = when {
                active == null || !active.imported -> getString(DesignR.string.home_need_profile)
                !clashRunning -> getString(DesignR.string.home_need_start)
                else -> null
            },
            attentionAction = when {
                active == null || !active.imported -> MainDesign.Request.OpenProfiles
                !clashRunning -> MainDesign.Request.ToggleStatus
                else -> null
            },
            safetyShort = homeSafetyShort(shown),
            networkShort = getString(
                if (clashRunning) DesignR.string.home_network_short_on else DesignR.string.home_network_short_off
            ),
            coreVersion = coreHeadline,
            coreSubtitle = coreSub,
            tunHeadline = tunHeadline,
            tunSubtitle = tunSubtitle,
            profileStackSummary = profileStackSummary,
            proxyBadge = proxyBadge,
            proxyHeadline = proxyHeadline,
            proxySubtitle = proxySubtitle,
            chainBadge = chainBadge,
            chainSubtitle = chainSub,
            connectedSub = connectedSub,
            safetySub = safetySub,
            policySub = policySub,
            networkSub = networkSub,
        )

        if (clashRunning) {
            fetchTraffic()
        }
    }

    private suspend fun MainDesign.runSelfCheck() {
        fetch()
        val state = withClash { queryTunnelState() }
        val shown = shownMode(state.mode)
        val active = withProfile { queryActive() }
        val scriptStore = ScriptDisplayStore(this@MainActivity)
        val leakKeys = listOf("DNS 走代理", "禁止系统 DNS", "关闭 IPv6", "嗅探防泄漏")
        val leakOnCount = leakKeys.count { scriptStore.optionOn(ScriptDisplayStore.GROUP_LEAK, it, true) }
        val chainSummary = active?.let { ChainStore(this@MainActivity).summary(it.uuid) }

        val groupNames = if (clashRunning) {
            runCatching { withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) } }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (clashRunning && groupNames.isNotEmpty()) {
            runCatching { withClash { healthCheck(groupNames.first()) } }
        }
        val primaryGroup = if (clashRunning && groupNames.isNotEmpty()) {
            runCatching { withClash { queryProxyGroup(groupNames.first(), uiStore.proxySort) } }
                .getOrNull()
        } else {
            null
        }
        fetch()

        val report = buildString {
            appendLine("1. 隧道连接：${if (clashRunning) "已连接 (运行正常)" else "未连接 (待机中)"}")
            appendLine("2. 当前配置：${active?.name ?: "未选择"}")
            appendLine("3. 出站策略：${homePolicy(active?.name, shown)}")
            if (clashRunning && primaryGroup != null && groupNames.isNotEmpty()) {
                appendLine("4. 主策略组：${groupNames.first()} → ${primaryGroup.now} (共 ${primaryGroup.proxies.size} 节点，已触发并发测速)")
            } else {
                appendLine("4. 主策略组：启动服务后可自动测速选路")
            }
            appendLine("5. 链式代理：${chainSummary ?: "未绑定双跳链路 (直通主策略组)"}")
            appendLine("6. 安全防泄漏：$leakOnCount / ${leakKeys.size} 项已开启")
        }.trim()

        showSelfCheckReport("2×2 服务状态实时自检报告", report)
    }

    private fun homeSafety(mode: TunnelState.Mode): String {
        if (mode == TunnelState.Mode.Direct) {
            return getString(DesignR.string.home_safety_direct)
        }
        val store = ScriptDisplayStore(this)
        val leak = listOf("DNS 走代理", "禁止系统 DNS", "关闭 IPv6", "嗅探防泄漏")
            .all { store.optionOn(ScriptDisplayStore.GROUP_LEAK, it, true) }
        return getString(if (leak) DesignR.string.home_safety_on else DesignR.string.home_safety_partial)
    }

    private fun homeSafetyShort(mode: TunnelState.Mode): String {
        if (mode == TunnelState.Mode.Direct) {
            return getString(DesignR.string.home_safety_short_direct)
        }
        val store = ScriptDisplayStore(this)
        val keys = listOf("DNS 走代理", "禁止系统 DNS", "关闭 IPv6", "嗅探防泄漏")
        val onCount = keys.count { store.optionOn(ScriptDisplayStore.GROUP_LEAK, it, true) }
        return if (onCount == keys.size) {
            getString(DesignR.string.home_safety_short_on)
        } else {
            "$onCount/${keys.size} 部分防护"
        }
    }

    private fun homePolicy(name: String?, mode: TunnelState.Mode): String {
        if (name.isNullOrBlank()) return getString(DesignR.string.not_selected)
        val modeLabel = getString(
            when (mode) {
                TunnelState.Mode.Global -> DesignR.string.global_mode
                TunnelState.Mode.Direct -> DesignR.string.direct_mode
                else -> DesignR.string.rule_mode
            }
        )
        return "$name · $modeLabel"
    }

    private suspend fun reloadActive() {
        if (!clashRunning) return
        val active = withProfile { queryActive() } ?: return
        sendProfileChanged(active.uuid)
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setTraffic(queryTrafficNow(), queryTrafficTotal())
        }
    }

    private suspend fun shownMode(running: TunnelState.Mode): TunnelState.Mode {
        val chosen = withClash {
            queryOverride(Clash.OverrideSlot.Session).mode
                ?: if (clashRunning) null else queryOverride(Clash.OverrideSlot.Persist).mode
        }
        return chosen ?: running
    }

    private suspend fun patchMode(mode: TunnelState.Mode) {
        withClash {
            val persist = queryOverride(Clash.OverrideSlot.Persist)
            persist.mode = mode
            patchOverride(Clash.OverrideSlot.Persist, persist)
            val session = queryOverride(Clash.OverrideSlot.Session)
            session.mode = mode
            patchOverride(Clash.OverrideSlot.Session, session)
        }
        design?.fetch()
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopService(LogcatService::class.intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setupShortcuts()
    }

    private fun setupShortcuts() {
        // Skip dynamic shortcut setup when the app icon is hidden.
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_launcher_foreground))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_launcher_foreground))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_launcher_foreground))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }

    companion object {
        private var launched = false
    }
}
