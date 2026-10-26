package com.github.kr328.clash

import com.github.kr328.clash.design.ui.AppEvents
import com.github.kr328.clash.design.RuleSwitchesDesign
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import com.github.kr328.clash.design.util.ConfigGroups
import com.github.kr328.clash.design.util.ConfigRuleSnapshot
import com.github.kr328.clash.design.util.ConfigRules
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 当前配置的规则开关。
 *
 * 这一页永远讲的是"当前配置"：该配置启用了覆写脚本，就显示脚本的分流开关；
 * 没启用，就是一份可以编辑的规则草稿；还没建草稿时，先把订阅自己的规则列出来。
 */
class RuleSwitchesActivity : BaseActivity<RuleSwitchesDesign>() {
    override suspend fun main() {
        val store = ScriptDisplayStore(this)
        val active = withProfile { queryActive() }

        // 1.4.5 及更早只记「关掉了哪几条」。第一次进这一页时把它们换算成
        // 一份等价的规则草稿，用户不会因为换了机制发现关掉的规则又回来了。
        if (active != null) {
            store.migrateDisabledRules(active.uuid, readRules(active).items)
        }

        val design = RuleSwitchesDesign(
            context = this,
            store = store,
            profileUuid = active?.uuid,
            profileName = active?.name,
            scriptOn = active?.let { store.profileScriptEnabled(it.uuid) } ?: false,
            rules = readRules(active),
            groups = readGroups(active),
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
                            // 首页的「脚本 ON / 无脚本」徽标靠这个事件更新：内核没跑时没有 reload 广播可等。
                            AppEvents.emit(AppEvents.Event.ScriptToggled(current.uuid, request.enabled))
                            if (clashRunning) {
                                sendProfileChanged(current.uuid)
                                design.updateSource(readRules(current), readGroups(current))
                            }
                        }
                        RuleSwitchesDesign.Request.ApplyRuleChange -> {
                            // 规则草稿已经落盘：让内核按新配置重载。
                            // 页面自己在回调里更新过状态，不重画（避免滚动位置被重置）。
                            val current = withProfile { queryActive() }
                            if (current != null && clashRunning) {
                                sendProfileChanged(current.uuid)
                            }
                        }
                        RuleSwitchesDesign.Request.Reload -> {
                            val current = withProfile { queryActive() }
                            if (current != null) {
                                if (clashRunning) sendProfileChanged(current.uuid)
                                design.updateSource(readRules(current), readGroups(current))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 原配置里真实生效的规则：直接读 service 那边落盘的 config.yaml（订阅 + 已应用的改动）。
     * 没导入完的配置还在 pending 目录里，这里一起兜住。订阅文件可能不小，放 IO 线程读。
     */
    private suspend fun readRules(profile: Profile?): ConfigRuleSnapshot {
        if (profile == null) return ConfigRuleSnapshot.EMPTY
        return withContext(Dispatchers.IO) {
            val dirs = listOf(importedDir, pendingDir)
            for (dir in dirs) {
                val snapshot = ConfigRules.read(File(dir, "${profile.uuid}/config.yaml"))
                if (snapshot.total > 0) return@withContext snapshot
            }
            ConfigRuleSnapshot.EMPTY
        }
    }

    /**
     * 配置里真实存在的策略组。编辑规则时目标只能从这里选 ——
     * 指向不存在的组会让内核解析直接失败（proxy [...] not found）。
     */
    private suspend fun readGroups(profile: Profile?): List<String> {
        if (profile == null) return emptyList()
        return withContext(Dispatchers.IO) {
            for (dir in listOf(importedDir, pendingDir)) {
                val names = ConfigGroups.read(File(dir, "${profile.uuid}/config.yaml"))
                if (names.isNotEmpty()) return@withContext names
            }
            emptyList()
        }
    }
}
