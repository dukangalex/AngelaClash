package com.github.kr328.clash

import com.github.kr328.clash.design.RuleSwitchesDesign
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

/**
 * 当前配置的策略组开关。策略组归属配置本身：只有该配置启用了覆写脚本时，
 * 这些开关才改写它的策略组。
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
                            if (clashRunning) sendProfileChanged(current.uuid)
                        }
                        RuleSwitchesDesign.Request.Reload -> {
                            val current = withProfile { queryActive() }
                            if (current != null && clashRunning) sendProfileChanged(current.uuid)
                        }
                    }
                }
            }
        }
    }
}
