package com.github.kr328.clash

import com.github.kr328.clash.design.EnhanceToolsDesign
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class EnhanceToolsActivity : BaseActivity<EnhanceToolsDesign>() {
    override suspend fun main() {
        val design = EnhanceToolsDesign(this, ServiceStore(this))
        setContentDesign(design)
        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive { }
            }
        }
    }
}
