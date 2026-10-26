package com.github.kr328.clash.design.store

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.model.AppInfoSort
import com.github.kr328.clash.design.model.DarkMode

class UiStore(context: Context) {
    private val store = Store(
        context
            .getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
            .asStoreProvider()
    )

    var enableVpn: Boolean by store.boolean(
        key = "enable_vpn",
        defaultValue = true
    )

    var darkMode: DarkMode by store.enum(
        key = "dark_mode",
        defaultValue = DarkMode.ForceDark,
        values = DarkMode.values()
    )

    var themeReady: Boolean by store.boolean(
        key = "theme_ready_v2",
        defaultValue = false,
    )

    init {
        if (!themeReady) {
            darkMode = DarkMode.ForceDark
            themeReady = true
        }
    }

    var hideAppIcon: Boolean by store.boolean(
        key = "hide_app_icon",
        defaultValue = context.packageManager.getComponentEnabledSetting(context.mainActivityAlias)
            .let { state ->
                state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
                        state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            },
    )

    var hideFromRecents: Boolean by store.boolean(
        key = "hide_from_recents",
        defaultValue = false,
    )

    var proxyExcludeNotSelectable by store.boolean(
        key = "proxy_exclude_not_selectable",
        defaultValue = false,
    )

    var proxyLine: Int by store.int(
        key = "proxy_line",
        defaultValue = 2
    )

    var proxyStyle: String by store.string(
        key = "proxy_style",
        defaultValue = "tab"
    )

    var proxySize: Int by store.int(
        key = "proxy_size",
        defaultValue = 0
    )

    var proxySort: ProxySort by store.enum(
        key = "proxy_sort",
        defaultValue = ProxySort.Default,
        values = ProxySort.values()
    )

    var proxyLastGroup: String by store.string(
        key = "proxy_last_group",
        defaultValue = ""
    )

    var accessControlSort: AppInfoSort by store.enum(
        key = "access_control_sort",
        defaultValue = AppInfoSort.Label,
        values = AppInfoSort.values(),
    )

    var accessControlReverse: Boolean by store.boolean(
        key = "access_control_reverse",
        defaultValue = false
    )

    var accessControlSystemApp: Boolean by store.boolean(
        key = "access_control_system_app",
        defaultValue = false,
    )

    var hapticFeedback: Boolean by store.boolean(
        key = "haptic_feedback",
        defaultValue = true,
    )

    var autoConnect: Boolean by store.boolean(
        key = "auto_connect",
        defaultValue = false,
    )

    var autoCheckUpdate: Boolean by store.boolean(
        key = "auto_check_update",
        defaultValue = false,
    )

    /**
     * 首页上被用户隐藏的区块（存 key，不存下标，方便以后加新块）。
     *
     * 存「隐藏了哪些」而不是「显示哪些」：以后新增区块默认可见，
     * 老用户不会因为升级而看不到新东西。
     */
    var homeHiddenBlocks: Set<String> by store.stringSet(
        key = "home_hidden_blocks",
        defaultValue = emptySet(),
    )

    /**
     * 首页网格的排布，按断点各存一份（手机 8 列 / 平板 12 列 / 大屏 16 列）。
     *
     * 格式：`key:w,h|key:w,h|...`，顺序就是显示顺序。存字符串而不是 JSON，
     * 是因为这一层只有 Store.string，多引一个序列化器不值当。
     */
    var homeGridCompact: String by store.string(key = "home_grid_compact", defaultValue = "")
    var homeGridMedium: String by store.string(key = "home_grid_medium", defaultValue = "")
    var homeGridWide: String by store.string(key = "home_grid_wide", defaultValue = "")

    /** 规则开关页里被收进「其他设置」的开关名。 */
    var hiddenRuleSwitches: Set<String> by store.stringSet(
        key = "hidden_rule_switches",
        defaultValue = emptySet(),
    )

    companion object {
        private const val PREFERENCE_NAME = "ui"

        val Context.mainActivityAlias: ComponentName
            get() = ComponentName(this, "com.github.kr328.clash.MainActivityAlias")
    }
}