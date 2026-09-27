package com.github.kr328.clash

import com.github.kr328.clash.design.RuleSwitchesDesign
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class RuleSwitchesActivity : BaseActivity<RuleSwitchesDesign>() {
    override suspend fun main() {
        val store = ScriptDisplayStore(this)
        val design = RuleSwitchesDesign(this, store)
        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive {
                    when (it) {
                        RuleSwitchesDesign.Request.Reload -> {
                            val active = withProfile { queryActive() }
                            if (active != null && clashRunning) sendProfileChanged(active.uuid)
                        }
                    }
                }
            }
        }
    }
}
