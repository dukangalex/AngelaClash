package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.resolveThemedResourceId
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.channels.Channel

class ProxyMenu(
    private val context: Context,
    private val menuView: View,
    private var mode: TunnelState.Mode?,
    private val uiStore: UiStore,
    private val requests: Channel<ProxyDesign.Request>,
    private val updateConfig: () -> Unit,
) : PopupMenu.OnMenuItemClickListener {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    fun show() {
        showSettingsSheet()
    }

    fun showOverflowMenu() {
        val popup = PopupMenu(context, menuView)
        popup.menuInflater.inflate(R.menu.menu_proxy, popup.menu)
        popup.menu.apply {
            findItem(R.id.not_selectable)?.isChecked = uiStore.proxyExcludeNotSelectable
            when (uiStore.proxyLine) {
                1 -> findItem(R.id.single)?.isChecked = true
                2 -> findItem(R.id.doubles)?.isChecked = true
                3 -> findItem(R.id.multiple)?.isChecked = true
            }
            when (uiStore.proxySort) {
                ProxySort.Default -> findItem(R.id.default_)?.isChecked = true
                ProxySort.Title -> findItem(R.id.name)?.isChecked = true
                ProxySort.Delay -> findItem(R.id.delay)?.isChecked = true
            }
            when (mode) {
                null -> findItem(R.id.dont_modify)?.isChecked = true
                TunnelState.Mode.Direct -> findItem(R.id.direct_mode)?.isChecked = true
                TunnelState.Mode.Global -> findItem(R.id.global_mode)?.isChecked = true
                TunnelState.Mode.Rule -> findItem(R.id.rule_mode)?.isChecked = true
                else -> {}
            }
        }
        popup.setOnMenuItemClickListener(this)
        popup.show()
    }

    fun showSettingsSheet() {
        val dialog = AppBottomSheetDialog(context)

        val colorPrimary = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val colorSelectedBg = context.resolveThemedColor(R.attr.clashSelectedSurface)
        val colorSurfaceVariant = context.resolveThemedColor(R.attr.clashSurfaceVariant)
        val colorOutline = context.resolveThemedColor(R.attr.clashOutline)
        val colorOnSurface = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val colorSecondary = context.resolveThemedColor(android.R.attr.textColorSecondary)
        val rippleRes = context.resolveThemedResourceId(android.R.attr.selectableItemBackground)

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(24))
        }

        // Centered "设置" title matching Screenshot ①
        val titleView = TextView(context).apply {
            text = "设置"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorOnSurface)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(14)
            }
        }
        root.addView(titleView)

        fun addSectionTitle(label: String) {
            root.addView(TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(colorSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(8)
                }
            })
        }

        data class PillRefs(
            val card: MaterialCardView,
            val text: TextView,
            val icon: ImageView?,
        )

        fun stylePill(pill: PillRefs, selected: Boolean) {
            if (selected) {
                pill.card.setCardBackgroundColor(colorSelectedBg)
                pill.card.strokeWidth = (1.5f * density).toInt().coerceAtLeast(2)
                pill.card.setStrokeColor(colorPrimary)
                pill.text.setTextColor(colorPrimary)
                pill.text.typeface = Typeface.DEFAULT_BOLD
                pill.icon?.setColorFilter(colorPrimary)
            } else {
                pill.card.setCardBackgroundColor(colorSurfaceVariant)
                pill.card.strokeWidth = dp(1)
                pill.card.setStrokeColor(colorOutline)
                pill.text.setTextColor(colorOnSurface)
                pill.text.typeface = Typeface.DEFAULT
                pill.icon?.setColorFilter(colorSecondary)
            }
        }

        fun createPillRow(
            options: List<Pair<String, Int?>>,
            selectedIndex: () -> Int,
            onSelect: (Int) -> Unit,
        ): LinearLayout {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val pills = mutableListOf<PillRefs>()

            options.forEachIndexed { idx, (label, iconRes) ->
                val card = MaterialCardView(context).apply {
                    radius = dp(12).toFloat()
                    cardElevation = 0f
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (idx > 0) marginStart = dp(10)
                    }
                }

                val inner = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setBackgroundResource(rippleRes)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setPadding(dp(10), 0, dp(10), 0)
                }

                val iconView = if (iconRes != null) {
                    ImageView(context).apply {
                        setImageResource(iconRes)
                        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                            marginEnd = dp(6)
                        }
                    }.also { inner.addView(it) }
                } else {
                    null
                }

                val labelView = TextView(context).apply {
                    text = label
                    textSize = 14f
                    gravity = Gravity.CENTER
                }
                inner.addView(labelView)
                card.addView(inner)

                val ref = PillRefs(card, labelView, iconView)
                pills += ref
                row.addView(card)

                card.setOnClickListener {
                    onSelect(idx)
                    val current = selectedIndex()
                    pills.forEachIndexed { i, p -> stylePill(p, i == current) }
                }
            }

            val initial = selectedIndex()
            pills.forEachIndexed { i, p -> stylePill(p, i == initial) }
            return row
        }

        // 1. 风格: 标签页 / 列表
        addSectionTitle("风格")
        root.addView(
            createPillRow(
                options = listOf(
                    "标签页" to R.drawable.ic_outline_folder,
                    "列表" to R.drawable.ic_baseline_view_list,
                ),
                selectedIndex = { if (uiStore.proxyStyle == "list") 1 else 0 },
                onSelect = { idx ->
                    val newStyle = if (idx == 1) "list" else "tab"
                    if (uiStore.proxyStyle != newStyle) {
                        uiStore.proxyStyle = newStyle
                        updateConfig()
                    }
                },
            )
        )

        // 2. 排序: 默认 / 延迟 / 名称
        addSectionTitle("排序")
        root.addView(
            createPillRow(
                options = listOf(
                    "默认" to null,
                    "延迟" to null,
                    "名称" to null,
                ),
                selectedIndex = {
                    when (uiStore.proxySort) {
                        ProxySort.Default -> 0
                        ProxySort.Delay -> 1
                        ProxySort.Title -> 2
                    }
                },
                onSelect = { idx ->
                    val newSort = when (idx) {
                        1 -> ProxySort.Delay
                        2 -> ProxySort.Title
                        else -> ProxySort.Default
                    }
                    if (uiStore.proxySort != newSort) {
                        uiStore.proxySort = newSort
                        requests.trySend(ProxyDesign.Request.ReloadAll)
                    }
                },
            )
        )

        // 3. 布局: 宽松 / 标准 / 紧凑
        addSectionTitle("布局")
        root.addView(
            createPillRow(
                options = listOf(
                    "宽松" to null,
                    "标准" to null,
                    "紧凑" to null,
                ),
                selectedIndex = { (uiStore.proxyLine - 1).coerceIn(0, 2) },
                onSelect = { idx ->
                    val newLine = (idx + 1).coerceIn(1, 3)
                    if (uiStore.proxyLine != newLine) {
                        uiStore.proxyLine = newLine
                        updateConfig()
                        requests.trySend(ProxyDesign.Request.ReloadAll)
                    }
                },
            )
        )

        // 4. 尺寸: 标准 / 紧凑 / 最小
        addSectionTitle("尺寸")
        root.addView(
            createPillRow(
                options = listOf(
                    "标准" to null,
                    "紧凑" to null,
                    "最小" to null,
                ),
                selectedIndex = { uiStore.proxySize.coerceIn(0, 2) },
                onSelect = { idx ->
                    val newSize = idx.coerceIn(0, 2)
                    if (uiStore.proxySize != newSize) {
                        uiStore.proxySize = newSize
                        updateConfig()
                        requests.trySend(ProxyDesign.Request.ReloadAll)
                    }
                },
            )
        )

        scroll.addView(root)
        dialog.setContentView(scroll)
        dialog.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        item.isChecked = !item.isChecked

        when (item.itemId) {
            R.id.not_selectable -> {
                uiStore.proxyExcludeNotSelectable = item.isChecked

                requests.trySend(ProxyDesign.Request.ReLaunch)
            }
            R.id.single -> {
                uiStore.proxyLine = 1

                updateConfig()

                requests.trySend(ProxyDesign.Request.ReloadAll)
            }
            R.id.doubles -> {
                uiStore.proxyLine = 2

                updateConfig()

                requests.trySend(ProxyDesign.Request.ReloadAll)
            }
            R.id.multiple -> {
                uiStore.proxyLine = 3

                updateConfig()

                requests.trySend(ProxyDesign.Request.ReloadAll)
            }
            R.id.default_ -> {
                uiStore.proxySort = ProxySort.Default

                requests.trySend(ProxyDesign.Request.ReloadAll)
            }
            R.id.name -> {
                uiStore.proxySort = ProxySort.Title

                requests.trySend(ProxyDesign.Request.ReloadAll)
            }
            R.id.delay -> {
                uiStore.proxySort = ProxySort.Delay

                requests.trySend(ProxyDesign.Request.ReloadAll)
            }
            R.id.dont_modify -> {
                mode = null
                requests.trySend(ProxyDesign.Request.PatchMode(null))
            }
            R.id.direct_mode -> {
                mode = TunnelState.Mode.Direct
                requests.trySend(ProxyDesign.Request.PatchMode(TunnelState.Mode.Direct))
            }
            R.id.global_mode -> {
                mode = TunnelState.Mode.Global
                requests.trySend(ProxyDesign.Request.PatchMode(TunnelState.Mode.Global))
            }
            R.id.rule_mode -> {
                mode = TunnelState.Mode.Rule
                requests.trySend(ProxyDesign.Request.PatchMode(TunnelState.Mode.Rule))
            }
            else -> return false
        }

        return true
    }
}
