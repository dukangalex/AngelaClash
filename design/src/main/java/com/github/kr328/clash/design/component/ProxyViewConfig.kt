package com.github.kr328.clash.design.component

import android.content.Context
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.resolveThemedResourceId

class ProxyViewConfig(
    val context: Context,
    var proxyLine: Int,
    var proxySize: Int = 0,
) {
    private val colorSurfaceVariant = context.resolveThemedColor(R.attr.clashSurfaceVariant)

    val clickableBackground =
        context.resolveThemedResourceId(android.R.attr.selectableItemBackground)

    val selectedControl = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
    val selectedBackground = context.resolveThemedColor(R.attr.clashSelectedSurface)

    val unselectedControl = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
    val unselectedBackground: Int
        get() = colorSurfaceVariant

    val outlineColor = context.resolveThemedColor(R.attr.clashOutline)
    val density = context.resources.displayMetrics.density

    val singleLine: Boolean
        get() = proxySize == 2

    val layoutPadding: Float
        get() = when (proxySize) {
            2 -> 3.0f * density
            1 -> 4.0f * density
            else -> context.getPixels(R.dimen.proxy_layout_padding).toFloat()
        }

    val contentPadding: Float
        get() {
            val base = if (proxyLine <= 2) {
                context.getPixels(R.dimen.proxy_content_padding).toFloat()
            } else {
                context.getPixels(R.dimen.proxy_content_padding_grid3).toFloat()
            }
            return when (proxySize) {
                2 -> base * 0.68f
                1 -> base * 0.80f
                else -> base
            }
        }

    val textMargin: Float
        get() {
            val base = if (proxyLine <= 2) {
                context.getPixels(R.dimen.proxy_text_margin).toFloat()
            } else {
                context.getPixels(R.dimen.proxy_text_margin_grid3).toFloat()
            }
            return when (proxySize) {
                2 -> 0f
                1 -> base * 0.65f
                else -> base
            }
        }

    val textSize: Float
        get() {
            val base = if (proxyLine <= 2) {
                context.getPixels(R.dimen.proxy_text_size).toFloat()
            } else {
                context.getPixels(R.dimen.proxy_text_size_grid3).toFloat()
            }
            return when (proxySize) {
                2 -> base * 0.88f
                1 -> base * 0.94f
                else -> base
            }
        }

    val cardRadius: Float
        get() {
            val base = context.getPixels(R.dimen.proxy_card_radius).toFloat()
            return when (proxySize) {
                2 -> base * 0.72f
                1 -> base * 0.85f
                else -> base
            }
        }

    var cardOffset = context.getPixels(R.dimen.proxy_card_offset).toFloat()
}
