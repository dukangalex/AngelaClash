package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override suspend fun main() {
        var mode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }
        var names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
        var states = List(names.size) { ProxyState("?") }
        var unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(10)

        val design = ProxyDesign(
            this,
            mode,
            names,
            uiStore
        )

        setContentDesign(design)

        design.requests.send(ProxyDesign.Request.ReloadAll)

        // 换配置 / 切脚本后重新读一遍组集合：能就地换就就地换，实在换不了才重建页面。
        suspend fun refreshGroups() {
            val newNames = withClash {
                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
            }
            val newMode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }

            if (design.reconcileGroups(newNames, newMode)) {
                if (newNames != names) {
                    names = newNames
                    states = List(names.size) { ProxyState("?") }
                    unorderedStates = names.indices.map { names[it] to states[it] }.toMap()

                    design.requests.send(ProxyDesign.Request.ReloadAll)
                }

                mode = newMode
            } else {
                startActivity(ProxyActivity::class.intent)

                finish()
            }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ProfileLoaded -> refreshGroups()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProxyDesign.Request.ReLaunch -> refreshGroups()
                        ProxyDesign.Request.ReloadAll -> {
                            names.indices.forEach { idx ->
                                design.requests.trySend(ProxyDesign.Request.Reload(idx))
                            }
                        }
                        is ProxyDesign.Request.Reload -> {
                            launch {
                                val group = reloadLock.withPermit {
                                    withClash {
                                        queryProxyGroup(names[it.index], uiStore.proxySort)
                                    }
                                }
                                val state = states[it.index]

                                state.now = group.now

                                design.updateGroup(
                                    it.index,
                                    group.proxies,
                                    group.type == "Selector",
                                    state,
                                    unorderedStates,
                                    group.type,
                                )
                            }
                        }
                        is ProxyDesign.Request.Select -> {
                            withClash {
                                patchSelector(names[it.index], it.name)

                                states[it.index].now = it.name
                            }

                            design.requestRedrawVisible()
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            launch {
                                withClash {
                                    healthCheck(names[it.index])
                                }

                                design.requests.send(ProxyDesign.Request.Reload(it.index))
                            }
                        }
                        ProxyDesign.Request.OpenRules ->
                            startActivity(RuleSwitchesActivity::class.intent)
                        is ProxyDesign.Request.PatchMode -> {
                            design.showModeSwitchTips()

                            withClash {
                                val o = queryOverride(Clash.OverrideSlot.Session)

                                o.mode = it.mode

                                patchOverride(Clash.OverrideSlot.Session, o)
                            }
                        }
                    }
                }
            }
        }
    }
}