package com.github.kr328.clash.design.adapter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.resolveThemedResourceId
import com.google.android.material.card.MaterialCardView

class ProxyGroupListAdapter(
    private val context: Context,
    private val config: ProxyViewConfig,
    groupNames: List<String>,
    private val onSelect: (groupIndex: Int, proxyName: String) -> Unit,
    private val onUrlTest: (groupIndex: Int) -> Unit,
    private val onLocate: (groupIndex: Int, proxyIndex: Int, proxyName: String) -> Unit = { _, _, _ -> },
) : RecyclerView.Adapter<ProxyGroupListAdapter.GroupHolder>() {

    class GroupItem(
        val index: Int,
        val name: String,
        var typeName: String = "Selector",
        var now: String = "",
        var selectable: Boolean = true,
        var expanded: Boolean = false,
        var urlTesting: Boolean = false,
        var allStates: List<ProxyViewState> = emptyList(),
        var filteredStates: List<ProxyViewState> = emptyList(),
    )

    private val sharedPool = RecyclerView.RecycledViewPool()
    private var searchQuery: String = ""

    val items: List<GroupItem> = groupNames.mapIndexed { idx, name ->
        GroupItem(
            index = idx,
            name = name,
            expanded = idx == 0,
        )
    }

    fun setSearchQuery(query: String) {
        val normalized = query.trim().lowercase()
        if (searchQuery == normalized) return
        searchQuery = normalized
        items.forEach { item ->
            applyFilter(item)
            if (searchQuery.isNotEmpty() && item.filteredStates.isNotEmpty()) {
                item.expanded = true
            }
        }
        notifyDataSetChanged()
    }

    fun updateGroup(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        groupType: String,
        parent: ProxyState,
        links: Map<String, ProxyState>,
    ) {
        if (position !in items.indices) return
        val item = items[position]
        item.selectable = selectable
        item.typeName = groupType.ifBlank { if (selectable) "Selector" else "URLTest" }
        item.now = parent.now
        item.urlTesting = false
        item.allStates = proxies.map { proxy ->
            val link = if (proxy.isGroup) links[proxy.name] else null
            ProxyViewState(config, proxy, parent, link)
        }
        applyFilter(item)
        notifyItemChanged(position)
    }

    fun setUrlTesting(position: Int, testing: Boolean) {
        if (position !in items.indices) return
        items[position].urlTesting = testing
        notifyItemChanged(position)
    }

    fun notifyConfigChanged() {
        notifyDataSetChanged()
    }

    fun locateExpandedOrFirstGroup(): Triple<Int, Int, String>? {
        if (items.isEmpty()) return null
        val targetItem = items.firstOrNull { it.expanded } ?: items.first()
        if (!targetItem.expanded) {
            targetItem.expanded = true
            notifyItemChanged(targetItem.index)
        }
        val proxyIdx = targetItem.filteredStates.indexOfFirst { it.proxy.name == targetItem.now }
        return Triple(targetItem.index, proxyIdx, targetItem.now)
    }

    private fun applyFilter(item: GroupItem) {
        if (searchQuery.isEmpty()) {
            item.filteredStates = item.allStates
        } else {
            item.filteredStates = item.allStates.filter {
                it.proxy.name.lowercase().contains(searchQuery) ||
                    it.proxy.title.lowercase().contains(searchQuery) ||
                    it.proxy.subtitle.lowercase().contains(searchQuery)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val colorSurfaceVariant = context.resolveThemedColor(R.attr.clashSurfaceVariant)
        val colorOutline = context.resolveThemedColor(R.attr.clashOutline)
        val colorOnSurface = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val colorSecondary = context.resolveThemedColor(android.R.attr.textColorSecondary)
        val colorPrimary = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val selectableBg = context.resolveThemedResourceId(android.R.attr.selectableItemBackgroundBorderless)

        val card = MaterialCardView(context).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setStrokeColor(colorOutline)
            setCardBackgroundColor(colorSurfaceVariant)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            setBackgroundResource(
                context.resolveThemedResourceId(android.R.attr.selectableItemBackground)
            )
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val titleView = TextView(context).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorOnSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val countBadge = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrimary)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(
                    android.graphics.Color.argb(
                        28,
                        android.graphics.Color.red(colorPrimary),
                        android.graphics.Color.green(colorPrimary),
                        android.graphics.Color.blue(colorPrimary),
                    )
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(6)
            }
        }

        titleLine.addView(titleView)
        titleLine.addView(countBadge)

        val subtitleLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(3)
            }
        }

        val subtitleView = TextView(context).apply {
            textSize = 13f
            setTextColor(colorSecondary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }

        val delayBadge = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(2), dp(6), dp(2))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        }

        subtitleLine.addView(subtitleView)
        subtitleLine.addView(delayBadge)

        titleCol.addView(titleLine)
        titleCol.addView(subtitleLine)

        // Locate / Target icon button (◎)
        val locateBtn = CrosshairIconView(context, colorSecondary).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginEnd = dp(4)
            }
            setBackgroundResource(selectableBg)
            isClickable = true
            isFocusable = true
        }

        // URL Test button + progress
        val testFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginEnd = dp(6)
            }
        }
        val testBtn = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setImageResource(R.drawable.ic_baseline_flash_on)
            setColorFilter(colorSecondary)
            setBackgroundResource(selectableBg)
        }
        val testProgress = ProgressBar(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            visibility = View.GONE
        }
        testFrame.addView(testBtn)
        testFrame.addView(testProgress)

        // Circular expand/collapse chevron button
        val chevronCircle = ChevronBadgeView(context, colorSecondary, colorOutline).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        }

        headerRow.addView(titleCol)
        headerRow.addView(locateBtn)
        headerRow.addView(testFrame)
        headerRow.addView(chevronCircle)

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
            setBackgroundColor(colorOutline)
            alpha = 0.55f
        }

        val childAdapter = ProxyAdapter(config) { _ -> }
        val gridView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(dp(8), dp(8), dp(8), dp(10))
            clipToPadding = false
            isNestedScrollingEnabled = false
            itemAnimator = null
            setRecycledViewPool(sharedPool)
            layoutManager = GridLayoutManager(context, 6).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return when (config.proxyLine) {
                            1 -> 6
                            2 -> 3
                            else -> 2
                        }
                    }
                }
            }
            adapter = childAdapter
        }

        container.addView(headerRow)
        container.addView(divider)
        container.addView(gridView)
        card.addView(container)

        return GroupHolder(
            card = card,
            headerRow = headerRow,
            titleView = titleView,
            countBadge = countBadge,
            subtitleView = subtitleView,
            delayBadge = delayBadge,
            locateBtn = locateBtn,
            testBtn = testBtn,
            testProgress = testProgress,
            chevronView = chevronCircle,
            divider = divider,
            gridView = gridView,
        )
    }

    private fun bindHeaderDelayBadge(badge: TextView, item: GroupItem) {
        val activeState = item.allStates.firstOrNull { it.proxy.name == item.now }
        val delay = activeState?.proxy?.delay ?: 0
        if (delay in 1..Short.MAX_VALUE) {
            val d = context.resources.displayMetrics.density
            val delayColor = when {
                delay < 200 -> android.graphics.Color.parseColor("#10B981")
                delay < 500 -> android.graphics.Color.parseColor("#F59E0B")
                else -> android.graphics.Color.parseColor("#EF4444")
            }
            badge.text = "$delay ms"
            badge.setTextColor(delayColor)
            badge.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * d
                setColor(
                    android.graphics.Color.argb(
                        32,
                        android.graphics.Color.red(delayColor),
                        android.graphics.Color.green(delayColor),
                        android.graphics.Color.blue(delayColor),
                    )
                )
            }
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
    }

    override fun onBindViewHolder(holder: GroupHolder, position: Int) {
        val item = items[position]

        holder.titleView.text = item.name
        val totalCount = item.allStates.size
        val filteredCount = item.filteredStates.size
        if (totalCount > 0) {
            holder.countBadge.text = if (searchQuery.isNotEmpty() && filteredCount != totalCount) {
                "$filteredCount/$totalCount"
            } else {
                "$totalCount"
            }
            holder.countBadge.visibility = View.VISIBLE
        } else {
            holder.countBadge.visibility = View.GONE
        }

        val nowLabel = item.now.ifBlank { "-" }
        holder.subtitleView.text = "${item.typeName} • $nowLabel"
        bindHeaderDelayBadge(holder.delayBadge, item)

        holder.chevronView.expanded = item.expanded
        holder.divider.visibility = if (item.expanded) View.VISIBLE else View.GONE
        holder.gridView.visibility = if (item.expanded) View.VISIBLE else View.GONE

        if (item.urlTesting) {
            holder.testBtn.visibility = View.GONE
            holder.testProgress.visibility = View.VISIBLE
        } else {
            holder.testBtn.visibility = View.VISIBLE
            holder.testProgress.visibility = View.GONE
        }

        holder.headerRow.setOnClickListener {
            item.expanded = !item.expanded
            notifyItemChanged(position)
        }

        holder.testBtn.setOnClickListener {
            item.urlTesting = true
            holder.testBtn.visibility = View.GONE
            holder.testProgress.visibility = View.VISIBLE
            onUrlTest(item.index)
        }

        holder.locateBtn.setOnClickListener {
            if (!item.expanded) {
                item.expanded = true
                notifyItemChanged(position)
            }
            val selectedIdx = item.filteredStates.indexOfFirst { it.proxy.name == item.now }
            onLocate(position, selectedIdx, item.now)
        }

        if (item.expanded) {
            val childAdapter = ProxyAdapter(config) { proxyName ->
                if (item.selectable) {
                    item.now = proxyName
                    holder.subtitleView.text = "${item.typeName} • $proxyName"
                    bindHeaderDelayBadge(holder.delayBadge, item)
                    onSelect(item.index, proxyName)
                    holder.gridView.adapter?.notifyDataSetChanged()
                }
            }.apply {
                selectable = item.selectable
                states = item.filteredStates
            }
            holder.gridView.swapAdapter(childAdapter, false)
        }
    }

    override fun getItemCount(): Int = items.size

    class GroupHolder(
        val card: MaterialCardView,
        val headerRow: View,
        val titleView: TextView,
        val countBadge: TextView,
        val subtitleView: TextView,
        val delayBadge: TextView,
        val locateBtn: View,
        val testBtn: ImageView,
        val testProgress: ProgressBar,
        val chevronView: ChevronBadgeView,
        val divider: View,
        val gridView: RecyclerView,
    ) : RecyclerView.ViewHolder(card)

    /**
     * Draws a crisp circular crosshair/target icon (◎) matching Screenshot ①.
     */
    class CrosshairIconView(context: Context, private val tintColor: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tintColor
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * context.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tintColor
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = context.resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val r = 6.5f * d
            val tick = 2.4f * d
            canvas.drawCircle(cx, cy, r, paint)
            canvas.drawCircle(cx, cy, 2.0f * d, dotPaint)
            canvas.drawLine(cx, cy - r - tick, cx, cy - r + 0.5f * d, paint)
            canvas.drawLine(cx, cy + r - 0.5f * d, cx, cy + r + tick, paint)
            canvas.drawLine(cx - r - tick, cy, cx - r + 0.5f * d, cy, paint)
            canvas.drawLine(cx + r - 0.5f * d, cy, cx + r + tick, cy, paint)
        }
    }

    /**
     * Draws the circular badge with up/down chevron matching Screenshot ①.
     */
    class ChevronBadgeView(
        context: Context,
        private val iconColor: Int,
        strokeColor: Int,
    ) : View(context) {
        var expanded: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = iconColor
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * context.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init {
            val d = context.resources.displayMetrics.density
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x18FFFFFF)
                setStroke((1f * d).toInt(), strokeColor)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = context.resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val dx = 4.2f * d
            val dy = 2.4f * d
            if (expanded) {
                canvas.drawLine(cx - dx, cy + dy * 0.5f, cx, cy - dy, paint)
                canvas.drawLine(cx, cy - dy, cx + dx, cy + dy * 0.5f, paint)
            } else {
                canvas.drawLine(cx - dx, cy - dy * 0.5f, cx, cy + dy, paint)
                canvas.drawLine(cx, cy + dy, cx + dx, cy - dy * 0.5f, paint)
            }
        }
    }
}
