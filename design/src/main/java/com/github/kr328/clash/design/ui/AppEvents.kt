package com.github.kr328.clash.design.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * 进程内跨页事件总线。
 *
 * 用 SharedFlow 而不是 Channel：几个 Activity 会同时活着（首页 / 代理页 / 脚本页 /
 * 规则开关页），同一个事件每个页面都要看到，Channel 会让其中一个把事件「吃掉」。
 *
 * 这里只做进程内通知，不承担持久化职责：真正的状态仍然在 `ScriptDisplayStore` 里，
 * 页面收到事件后应该重新读一遍，不要把事件内容当成状态。
 */
object AppEvents {
    sealed class Event {
        /**
         * 某份配置的覆写脚本开关变了。
         *
         * 以前只有内核 reload 时的服务广播会带出这个变化，所以**内核没在跑**的时候
         * 去配置页拨脚本，首页的「脚本 ON / 无脚本」徽标会一直是旧的。
         * 这个事件就是补这条路径的。
         */
        data class ScriptToggled(val uuid: UUID, val enabled: Boolean) : Event()

        /**
         * 某一个开关分组（`ScriptDisplayStore.GROUP_*`）里的选项被改了。
         *
         * 同一批开关在三个地方都能改：首页快捷 chip、脚本页分组、规则开关页。
         * 以前谁改完都只管自己重画，另外两处要等你退出再进来才更新。
         *
         * [group] 为空表示「不确定是哪个分组，全都重新读一遍」——
         * 批量操作（全开/全关、一次改多个 key）走这条。
         */
        data class OptionsChanged(val group: String? = null) : Event()

        /** 脚本内容变了（编辑保存 / 从链接同步）。策略组结构可能因此改变。 */
        data object ScriptContentChanged : Event()
    }

    // extraBufferCapacity 保证 tryEmit 在没有订阅者时也不会丢事件。
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)

    val events: SharedFlow<Event> = _events

    fun emit(event: Event) {
        _events.tryEmit(event)
    }
}
