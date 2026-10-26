package com.github.kr328.clash.design.ui

import android.graphics.Color

/**
 * 延迟配色。
 *
 * 以前这套阈值和颜色在 [com.github.kr328.clash.design.adapter.ProxyGroupListAdapter]
 * 和 [com.github.kr328.clash.design.component.ProxyViewState] 里各写了一份，
 * 改一处另一处不会跟着变，两个列表里同一个节点可能显示成两种颜色。
 *
 * 四档而不是三档：200/500 两档把「能用」和「很慢」混在一起，
 * 900ms 以上其实已经该被当成失败来看。
 */
object DelayPalette {
    /** 正常 */
    val OK = Color.parseColor("#34D399")

    /** 偏慢，但能用 */
    val FAIR = Color.parseColor("#FBBF24")

    /** 慢，明显影响体验 */
    val SLOW = Color.parseColor("#FB923C")

    /** 基本不可用 */
    val BAD = Color.parseColor("#F87171")

    /** 超时 / 未测 */
    val UNKNOWN = Color.parseColor("#94A3B8")

    fun color(delay: Int): Int = when {
        delay <= 0 -> UNKNOWN
        delay < 150 -> OK
        delay < 300 -> FAIR
        delay < 900 -> SLOW
        else -> BAD
    }

    /** 同色 20% 透明底，做胶囊背景。 */
    fun softColor(delay: Int): Int = withAlpha(color(delay), 0x33)

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
