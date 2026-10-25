package com.github.kr328.clash

import com.github.kr328.clash.design.RuleSwitchesDesign
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

/**
 * 当前配置的规则开关。
 *
 * 这一页永远讲的是"当前配置"：该配置启用了覆写脚本，就显示脚本的分流开关；
 * 没启用，就只列出它自己的策略组（订阅原规则），不提供任何会改动配置的开关。
 */
class RuleSwitchesActivity : BaseActivity<RuleSwitchesDesign>() {
    override suspend fun main() {
        val store = ScriptDisplayStore(this)
        val active = withProfile { queryActive() }
        val design = RuleSwitchesDesign(
            context = this,
            store = store,
            profileUuid = active?.uuid,
            profileName = active?.name,
            scriptOn = active?.let { store.profileScriptEnabled(it.uuid) } ?: false,
            groups = queryGroups(),
        )
        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive { request ->
                    when (request) {
                        is RuleSwitchesDesign.Request.ToggleScript -> {
                            val current = withProfile { queryActive() }
                            if (current == null) {
                                design.setScriptEnabled(false)
                                return@onReceive
                            }
                            store.setProfileScriptEnabled(current.uuid, request.enabled)
                            design.setScriptEnabled(request.enabled)
                            if (clashRunning) {
                                sendProfileChanged(current.uuid)
                                design.updateGroups(queryGroups())
                            }
                        }
                        RuleSwitchesDesign.Request.Reload -> {
                            val current = withProfile { queryActive() }
                            if (current != null && clashRunning) {
                                sendProfileChanged(current.uuid)
                                design.updateGroups(queryGroups())
                            }
                        }
                    }
                }
            }
        }
    }

    /** Groups the running config has for the active profile; empty while stopped. */
    private suspend fun queryGroups(): List<String> {
        if (!clashRunning) return emptyList()
        return runCatching { withClash { queryProxyGroupNames(false) } }.getOrDefault(emptyList())
    }
}
