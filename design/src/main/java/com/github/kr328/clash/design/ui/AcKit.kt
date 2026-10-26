package com.github.kr328.clash.design.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.card.MaterialCardView

/**
 * BettBox 式的基础视觉件。
 *
 * 设计约定（对齐 BettBox 的 CommonCard / SectionContainer / ListHeader）：
 *
 *  - 卡片：一个圆角面板 + 一圈 outlineVariant 描边，没有阴影。整屏靠描边分层，
 *    不靠 elevation，深浅色都稳定。
 *  - 分组：组标题单独一行放在卡片外面，一组内容放进同一张卡，组内用 indent 16dp
 *    的细分隔线分开。这样一屏能排下更多条目，层级也比「一条一张卡」清楚。
 *  - 组标题：13sp w600 + 副标题 12sp outline 色，右侧放操作按钮。
 *
 * 这三个件是所有页面的共同底座；新页面一律用它们拼，不要各自造卡片。
 */
object Ac {
    /** 卡片圆角：列表容器用 18，独立卡片用 20。 */
    const val RADIUS_LIST = 18
    const val RADIUS_CARD = 20

    /** 组内分隔线的左右缩进。 */
    private const val DIVIDER_INDENT = 16

    /** 组标题上方的留白。 */
    private const val HEADER_TOP = 20

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun primary(context: Context): Int =
        context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)

    fun onSurface(context: Context): Int =
        context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)

    fun secondary(context: Context): Int =
        context.resolveThemedColor(android.R.attr.textColorSecondary)

    fun outline(context: Context): Int =
        context.resolveThemedColor(R.attr.clashOutline)

    private fun surface(context: Context): Int =
        context.resolveThemedColor(R.attr.clashSurfaceVariant)

    /**
     * 一张卡片。
     *
     * @param padded 内容是否由卡片自己加内边距；列表行通常自己控制，传 false。
     */
    fun card(
        context: Context,
        content: View,
        radius: Int = RADIUS_LIST,
        padded: Boolean = false,
        onTap: (() -> Unit)? = null,
    ): MaterialCardView = MaterialCardView(context).apply {
        this.radius = dp(context, radius).toFloat()
        cardElevation = 0f
        strokeWidth = dp(context, 1)
        strokeColor = outline(context)
        setCardBackgroundColor(surface(context))
        isClickable = onTap != null
        isFocusable = onTap != null
        if (padded) {
            val pad = dp(context, 14)
            setContentPadding(pad, pad, pad, pad)
        }
        if (onTap != null) {
            setOnClickListener { onTap() }
        }
        addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** 组标题：[subtitle] 用来说明这一组现在是什么语义。 */
    fun header(
        context: Context,
        title: CharSequence,
        subtitle: CharSequence? = null,
        action: View? = null,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 16), dp(context, HEADER_TOP), dp(context, 8), dp(context, 8))

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(context).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ColorUtils.setAlphaComponent(onSurface(context), 0xC7))
                },
            )
            if (subtitle != null) {
                addView(
                    TextView(context).apply {
                        text = subtitle
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(outline(context))
                        setPadding(0, dp(context, 2), 0, 0)
                    },
                )
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        addView(labels)
        if (action != null) addView(action)
    }

    /**
     * 一组内容：标题 + 一张卡片 + 组内分隔线。
     *
     * 组内条目自己不要再套卡片，否则会出现卡片叠卡片。
     */
    fun section(
        context: Context,
        title: CharSequence,
        rows: List<View>,
        subtitle: CharSequence? = null,
        action: View? = null,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (title.isNotEmpty()) addView(header(context, title, subtitle, action))
        if (rows.isEmpty()) return@apply

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            rows.forEachIndexed { index, row ->
                addView(row)
                if (index != rows.lastIndex) addView(divider(context))
            }
        }
        addView(
            card(context, body, radius = RADIUS_LIST, padded = false),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(context, 16)
                rightMargin = dp(context, 16)
            },
        )
    }

    /** 组内分隔线。 */
    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(ColorUtils.setAlphaComponent(outline(context), 0x66))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 1),
        ).apply {
            marginStart = dp(context, DIVIDER_INDENT)
            marginEnd = dp(context, DIVIDER_INDENT)
        }
    }

    /** 类型标签：规则类型、协议名这类短标记。 */
    fun chip(context: Context, text: CharSequence): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(primary(context))
        setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3))
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 8).toFloat()
            setColor(ColorUtils.setAlphaComponent(primary(context), 0x1F))
        }
    }

    /** 单行文本；[secondary] 用弱化的颜色。 */
    fun label(
        context: Context,
        value: CharSequence,
        sizeSp: Float = 14f,
        secondary: Boolean = false,
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(if (secondary) Ac.secondary(context) else onSurface(context))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** 竖向堆叠的容器，省掉每处都写一遍 orientation。 */
    fun column(context: Context, padTop: Int = 0, padBottom: Int = 0): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, padTop), 0, dp(context, padBottom))
        }

    /** 可点击的次要按钮（文字型）。 */
    fun action(context: Context, label: CharSequence, onTap: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(primary(context))
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
        }

    /** 行容器：最小 48dp 高，避免点不中。 */
    fun row(context: Context, content: View, onTap: (() -> Unit)? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 48)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 12), dp(context, 12))
            isClickable = onTap != null
            isFocusable = onTap != null
            if (onTap != null) setOnClickListener { onTap() }
            addView(content)
        }
}
