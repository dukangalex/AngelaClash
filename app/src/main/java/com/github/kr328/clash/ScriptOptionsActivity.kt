package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.ScriptOptionsDesign
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class ScriptOptionsActivity : BaseActivity<ScriptOptionsDesign>() {
    override suspend fun main() {
        val store = ScriptDisplayStore(this)
        val design = ScriptOptionsDesign(this, store, clashRunning)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ScriptOptionsDesign.Request.EditScript ->
                            startActivity(ScriptEditorActivity::class.intent)
                        ScriptOptionsDesign.Request.Reload -> {
                            val active = withProfile { queryActive() }
                            if (active != null && clashRunning) sendProfileChanged(active.uuid)
                        }
                    }
                }
            }
        }
    }
}
