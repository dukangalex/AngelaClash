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
import com.github.kr328.clash.service.store.ChainStore
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        val active = withProfile { queryActive() }
        setProfileName(active?.name)
        val summary = active?.let { ChainStore(this@MainActivity).summary(it.uuid) }
        setChainSummary(summary)
        // Chaining is a built-in capability, so keep its entry visible before the first binding exists.
        setFeatureVisibility(ScriptDisplayStore(this@MainActivity).scriptEnabled(), true)
        setHome(
            connected = getString(if (clashRunning) DesignR.string.home_connected_on else DesignR.string.home_connected_off),
            safety = homeSafety(state.mode),
            policy = homePolicy(active?.name, state.mode),
            network = getString(if (clashRunning) DesignR.string.home_network_on else DesignR.string.home_network_off),
            attention = when {
                active == null || !active.imported -> getString(DesignR.string.home_need_profile)
                !clashRunning -> getString(DesignR.string.home_need_start)
                else -> null
            },
            opensProfiles = active == null || !active.imported,
        )
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

    private suspend fun patchMode(mode: TunnelState.Mode) {
        if (!clashRunning) return
        withClash {
            val override = queryOverride(Clash.OverrideSlot.Session)
            override.mode = mode
            patchOverride(Clash.OverrideSlot.Session, override)
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
