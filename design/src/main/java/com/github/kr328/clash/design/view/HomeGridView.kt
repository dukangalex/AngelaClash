package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import com.github.kr328.clash.design.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 首页的可编辑网格，对齐 BettBox 的 dashboard 布局。
 *
 * 硬规则（照抄 BettBox 的 `dashboard_metrics.dart`）：
 * - 一行切成 8 / 12 / 16 列：宽度 < 520dp 是 8 列（手机），520–880 是 12 列，再往上 16 列。
 * - 卡片跨列数 = 尺寸档 × 4。所以「小」占 4 列（手机一行两张），「宽 / 大」占 8 列（独占一行）。
 * - 行高基准 84dp，高的卡片占 2 行。
 *
 * 为什么不用 RecyclerView：首页这些卡片都是 `design_main.xml` 里的 databinding 视图，
 * 塞进 RecyclerView 等于把每张卡的绑定全部重做一遍，风险远大于收益。做成普通 ViewGroup
 * 反而能把「占几列几行」写成子视图属性。
 *
 * 交互全程无动效：
 * - 长按任意卡片进编辑模式；
 * - 编辑模式下按住卡片拖到别处松手即换位；
 * - 每张卡片左上角是尺寸角标（点一下循环切换），右上角是收起角标（收起不是删除，
 *   随时能从自定义首页里勾回来）。
 */
class HomeGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    interface Listener {
        /** 进出编辑模式，宿主用来显示顶部那条编辑栏。 */
        fun onEditModeChanged(editing: Boolean)

        /** 排布变了（换位、改尺寸），宿主负责落盘。 */
        fun onLayoutChanged()

        /** 卡片被收起。 */
        fun onTileHidden(key: String)

        /**
         * 卡片尺寸变了。真按 BettBox 的做法，同一张卡在窄档和宽档是两套布局、
         * 不是同一份布局缩放，所以这里把尺寸报给宿主，由宿主决定收掉哪些内容。
         */
        fun onTileSizeChanged(key: String, spanW: Int, spanH: Int)
    }

    private class Slot(
        val key: String,
        val view: View,
        val spanW: Int,
        val spanH: Int,
        val bounds: Rect = Rect(),
    )

    private enum class Band(val columns: Int) {
        Compact(8),
        Medium(12),
        Wide(16),
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val gap = (GAP_DP * density).roundToInt()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val unitHeight = (UNIT_DP * density).roundToInt()

    private val order = ArrayList<String>()
    private val sizeOf = HashMap<String, IntArray>()
    private val minSizeOf = HashMap<String, IntArray>()
    private val tiles = LinkedHashMap<String, View>()
    private var slots: List<Slot> = emptyList()

    private val overlays = ArrayList<View>()

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0x8C5B8CFF.toInt()
    }

    var editMode: Boolean = false
        private set

    private var dragView: View? = null
    private var dragKey: String? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** 手动缩放的进行态：按住右下角手柄拖动时才有值。 */
    private var resizeKey: String? = null
    private var resizeStartW = 1
    private var resizeStartH = 1
    private var resizeDownX = 0f
    private var resizeDownY = 0f
    private var unitWidth = 0f
    private var dragged = false
    private var swallowUp = false
    private val reportedSize = HashMap<String, Int>()
    private var pending: List<Triple<String, Int, Int>>? = null

    private val longPress = Runnable {
        if (editMode) {
            dragKey?.let { beginDrag(it) }
        } else if (dragKey != null) {
            // 长按任意卡片进编辑模式，并吞掉这一下的抬手，免得顺带点开卡片。
            swallowUp = true
            setEditMode(true)
        }
    }

    // ---------------------------------------------------------------- 对外

    /**
     * 灌入排布。[entries] 是 (key, 宽档, 高档)，顺序即显示顺序；
     * 没出现在 [entries] 里的卡片按默认值排在后面。
     */
    fun applyLayout(entries: List<Triple<String, Int, Int>>) {
        pending = entries
        if (tiles.isNotEmpty()) {
            consumePending()
            requestLayout()
        }
    }

    /**
     * 把 [pending] 里存的排布落到 order / sizeOf 上。
     *
     * 卡片是按 key 认的，而 key 要等 XML 把子视图都 add 完才拿得到，
     * 所以这里先对一次账：存过位置的按存的位置排，没存过的按 XML 里的默认值排在后面。
     */
    private fun consumePending() {
        val entries = pending ?: return
        pending = null
        order.clear()
        sizeOf.clear()
        entries.forEach { (key, w, h) ->
            if (key in tiles) {
                order.add(key)
                sizeOf[key] = intArrayOf(w.coerceIn(1, 2), h.coerceIn(1, MAX_SPAN_H))
            }
        }
        tiles.keys.forEach { key ->
            if (key !in sizeOf) {
                order.add(key)
                sizeOf[key] = minSizeOf[key]?.copyOf() ?: intArrayOf(1, 1)
            }
        }
    }

    /**
     * 把带 `app:tileKey` 的子视图登记成卡片。
     *
     * 不用 `onViewAdded`：那个回调是 API 23 才有的，`minSdk` 是 21，老机器上它永远不来。
     */
    private fun collectTiles() {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (overlays.contains(child)) continue
            val lp = child.layoutParams as? LayoutParams ?: continue
            val key = lp.tileKey ?: continue
            if (key in tiles) continue
            tiles[key] = child
            minSizeOf[key] = intArrayOf(lp.minSpanW, lp.minSpanH)
            sizeOf[key] = intArrayOf(lp.spanW, lp.spanH)
            order.add(key)
        }
    }

    fun captureLayout(): List<Triple<String, Int, Int>> =
        order.mapNotNull { key ->
            val size = sizeOf[key] ?: return@mapNotNull null
            Triple(key, size[0], size[1])
        }

    fun setEditMode(editing: Boolean) {
        if (editMode == editing) return
        editMode = editing
        if (editing) buildOverlays() else clearOverlays()
        listener?.onEditModeChanged(editing)
        requestLayout()
        invalidate()
    }

    fun resetLayout() {
        pending = null
        order.clear()
        sizeOf.clear()
        tiles.forEach { (key, _) ->
            order.add(key)
            sizeOf[key] = minSizeOf[key]?.copyOf() ?: intArrayOf(1, 1)
        }
        requestLayout()
        listener?.onLayoutChanged()
        post { if (editMode) buildOverlays() }
    }

    // ---------------------------------------------------------------- 子视图

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(lp: ViewGroup.LayoutParams): LayoutParams =
        LayoutParams(lp)

    inner class LayoutParams : ViewGroup.LayoutParams {
        var tileKey: String? = null
        var spanW = 1
        var spanH = 1
        var minSpanW = 1
        var minSpanH = 1

        constructor(width: Int, height: Int) : super(width, height)

        constructor(source: ViewGroup.LayoutParams) : super(source) {
            if (source is LayoutParams) {
                tileKey = source.tileKey
                spanW = source.spanW
                spanH = source.spanH
                minSpanW = source.minSpanW
                minSpanH = source.minSpanH
            }
        }

        constructor(c: Context, attrs: AttributeSet) : super(c, attrs) {
            val a = c.obtainStyledAttributes(attrs, R.styleable.HomeGridTile)
            tileKey = a.getString(R.styleable.HomeGridTile_tileKey)
            spanW = a.getInt(R.styleable.HomeGridTile_tileSpanW, 1).coerceIn(1, 2)
            spanH = a.getInt(R.styleable.HomeGridTile_tileSpanH, 1).coerceIn(1, MAX_SPAN_H)
            minSpanW = a.getInt(R.styleable.HomeGridTile_tileMinSpanW, 1).coerceIn(1, 2)
            minSpanH = a.getInt(R.styleable.HomeGridTile_tileMinSpanH, 1).coerceIn(1, MAX_SPAN_H)
            if (spanW < minSpanW) spanW = minSpanW
            if (spanH < minSpanH) spanH = minSpanH
            a.recycle()
        }
    }

    // ---------------------------------------------------------------- 排版

    private fun bandFor(width: Int): Band {
        val dp = width / density
        return when {
            dp < 520f -> Band.Compact
            dp < 880f -> Band.Medium
            else -> Band.Wide
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        collectTiles()
        consumePending()
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        if (width <= 0) {
            setMeasuredDimension(width, 0)
            return
        }
        val columns = bandFor(width).columns
        val colWidth = (width - gap * (columns - 1)).toFloat() / columns
        unitWidth = TILE_CELL * colWidth + (TILE_CELL - 1) * gap

        val rows = ArrayList<BooleanArray>()
        fun free(row: Int, col: Int, w: Int, h: Int): Boolean {
            for (r in row until row + h) {
                if (r >= rows.size) continue
                val line = rows[r]
                for (c in col until col + w) {
                    if (line[c]) return false
                }
            }
            return true
        }

        fun occupy(row: Int, col: Int, w: Int, h: Int) {
            while (rows.size < row + h) rows.add(BooleanArray(columns))
            for (r in row until row + h) {
                for (c in col until col + w) rows[r][c] = true
            }
        }

        val built = ArrayList<Slot>()
        order.forEach { key ->
            val view = tiles[key] ?: return@forEach
            if (view.visibility == View.GONE) return@forEach
            val size = sizeOf.getOrPut(key) { minSizeOf[key]?.copyOf() ?: intArrayOf(1, 1) }
            val spanW = size[0].coerceIn(1, 2)
            val spanH = size[1].coerceIn(1, MAX_SPAN_H)
            val cells = spanW * TILE_CELL

            var placedRow = 0
            var placedCol = 0
            var found = false
            while (placedRow <= rows.size) {
                var col = 0
                while (col <= columns - cells) {
                    if (free(placedRow, col, cells, spanH)) {
                        placedCol = col
                        found = true
                        break
                    }
                    col += 1
                }
                if (found) break
                placedRow += 1
            }
            if (!found) {
                placedRow = rows.size
                placedCol = 0
            }
            occupy(placedRow, placedCol, cells, spanH)

            val left = (placedCol * (colWidth + gap)).roundToInt()
            val top = placedRow * (unitHeight + gap)
            val w = (cells * colWidth + (cells - 1) * gap).roundToInt()
            val h = spanH * unitHeight + (spanH - 1) * gap
            built.add(Slot(key, view, spanW, spanH, Rect(left, top, left + w, top + h)))
        }
        slots = built

        val sizeChanges = slots.filter { slot ->
            reportedSize[slot.key] != slot.spanW * 10 + slot.spanH
        }

        var contentBottom = 0
        slots.forEach { slot ->
            slot.view.measure(
                View.MeasureSpec.makeMeasureSpec(slot.bounds.width(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(slot.bounds.height(), View.MeasureSpec.EXACTLY),
            )
            if (slot.bounds.bottom > contentBottom) contentBottom = slot.bounds.bottom
        }
        overlays.forEach { overlay ->
            val side = View.MeasureSpec.makeMeasureSpec(OVERLAY_DP.dp(), View.MeasureSpec.EXACTLY)
            overlay.measure(side, side)
        }

        setMeasuredDimension(
            width,
            View.resolveSize(contentBottom + paddingTop + paddingBottom, heightMeasureSpec),
        )

        if (sizeChanges.isNotEmpty()) {
            sizeChanges.forEach { reportedSize[it.key] = it.spanW * 10 + it.spanH }
            post {
                sizeChanges.forEach { slot ->
                    listener?.onTileSizeChanged(slot.key, slot.spanW, slot.spanH)
                }
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val offset = paddingTop
        slots.forEach { slot ->
            slot.view.layout(
                slot.bounds.left,
                slot.bounds.top + offset,
                slot.bounds.right,
                slot.bounds.bottom + offset,
            )
        }
        layoutOverlays(offset)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (editMode) {
            slots.forEach { slot ->
                val bounds = slot.bounds
                canvas.drawRoundRect(
                    bounds.left + density,
                    bounds.top + paddingTop + density,
                    bounds.right - density,
                    bounds.bottom + paddingTop - density,
                    20f * density,
                    20f * density,
                    edgePaint,
                )
            }
        }
        super.dispatchDraw(canvas)
    }

    // ---------------------------------------------------------------- 角标

    private fun buildOverlays() {
        clearOverlays()
        slots.forEach { slot ->
            overlays += overlayButton(sizeLabel(slot.spanW, slot.spanH)) { cycleSize(slot.key) }
            overlays += overlayButton("×") { hideTile(slot.key) }
            overlays += overlayButton("↘") { }.apply {
                // 手柄自己不吃点击，交给 dispatchTouchEvent 做连续缩放
                isClickable = false
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xCC5B8CFF.toInt())
                    setStroke((1.5f * density).toInt(), 0xFFFFFFFF.toInt())
                }
            }
        }
        overlays.forEach { addViewInLayout(it, -1, LayoutParams(OVERLAY_DP.dp(), OVERLAY_DP.dp()), true) }
        requestLayout()
    }

    private fun overlayButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xE65B8CFF.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun sizeLabel(w: Int, h: Int): String = "${if (w == 1) 1 else 2}×$h"

    private fun clearOverlays() {
        overlays.toList().forEach { removeViewInLayout(it) }
        overlays.clear()
    }

    private fun layoutOverlays(offset: Int) {
        if (overlays.isEmpty()) return
        val side = OVERLAY_DP.dp()
        val margin = 6.dp()
        slots.forEachIndexed { index, slot ->
            val size = overlays.getOrNull(index * 3) ?: return@forEachIndexed
            val hide = overlays.getOrNull(index * 3 + 1) ?: return@forEachIndexed
            val handle = overlays.getOrNull(index * 3 + 2) ?: return@forEachIndexed
            val bounds = slot.bounds
            size.layout(
                bounds.left + margin,
                bounds.top + offset + margin,
                bounds.left + margin + side,
                bounds.top + offset + margin + side,
            )
            hide.layout(
                bounds.right - margin - side,
                bounds.top + offset + margin,
                bounds.right - margin,
                bounds.top + offset + margin + side,
            )
            handle.layout(
                bounds.right - margin - side,
                bounds.bottom + offset - margin - side,
                bounds.right - margin,
                bounds.bottom + offset - margin,
            )
        }
    }

    private fun cycleSize(key: String) {
        val current = sizeOf.getOrPut(key) { intArrayOf(1, 1) }
        val minW = minSizeOf[key]?.get(0) ?: 1
        val minH = minSizeOf[key]?.get(1) ?: 1
        val cycle = listOf(1 to 1, 2 to 1, 1 to 2, 2 to 2)
            .filter { it.first >= minW && it.second >= minH }
        if (cycle.isEmpty()) return
        val at = cycle.indexOfFirst { it.first == current[0] && it.second == current[1] }
        val next = cycle[if (at < 0) 0 else (at + 1) % cycle.size]
        sizeOf[key] = intArrayOf(next.first, next.second)
        listener?.onLayoutChanged()
        clearOverlays()
        requestLayout()
        post { if (editMode) buildOverlays() }
    }

    private fun hideTile(key: String) {
        val view = tiles[key] ?: return
        view.visibility = View.GONE
        listener?.onTileHidden(key)
        clearOverlays()
        requestLayout()
        post { if (editMode) buildOverlays() }
    }

    // ---------------------------------------------------------------- 拖动

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 手动缩放优先：按住右下角手柄，尺寸按格子吸附着走
        if (resizeKey != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    applyResize(ev.x, ev.y)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    finishResize()
                    return true
                }
                MotionEvent.ACTION_DOWN -> return true
            }
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                lastY = ev.y
                dragged = false
                swallowUp = false
                val handle = resizeHandleAt(ev.x, ev.y)
                if (handle != null) {
                    beginResize(handle, ev.x, ev.y)
                    return true
                }
                if (!onOverlay(ev.x, ev.y)) {
                    val slot = slotAt(ev.x, ev.y)
                    if (slot != null) {
                        dragKey = slot.key
                        removeCallbacks(longPress)
                        postDelayed(longPress, 280)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragView == null && (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop)) {
                    removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragView != null) {
                    endDrag()
                    return true
                }
                dragKey = null
                if (swallowUp) {
                    swallowUp = false
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        dragView != null || super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val view = dragView ?: return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                dragged = true
                lastX = ev.x
                lastY = ev.y
                view.translationX = ev.x - downX
                view.translationY = ev.y - downY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
            else -> Unit
        }
        return true
    }

    private fun beginDrag(key: String) {
        val view = tiles[key] ?: return
        val slot = slots.firstOrNull { it.key == key } ?: return
        dragView = view
        view.elevation = 12f * density
        parent?.requestDisallowInterceptTouchEvent(true)
        val cancel = MotionEvent.obtain(
            System.currentTimeMillis(), System.currentTimeMillis(),
            MotionEvent.ACTION_CANCEL, downX, downY, 0,
        )
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        @Suppress("UNUSED_EXPRESSION")
        slot
    }

    private fun endDrag() {
        val key = dragKey
        val view = dragView
        val moved = dragged
        val fingerX = lastX
        val fingerY = lastY

        // 落点要在复位之前算：之前先清了 dragKey、又把 translation 归零，
        // 算出来的「最近的一张卡」就是它自己，于是永远不换位、看起来像跳回去。
        val target = if (moved && key != null) dropIndexAt(fingerX, fingerY, key) else -1

        dragView = null
        dragKey = null
        dragged = false
        view?.let {
            it.translationX = 0f
            it.translationY = 0f
            it.elevation = 0f
        }

        if (target >= 0 && key != null) {
            val from = order.indexOf(key)
            if (from >= 0 && target != from) {
                order.removeAt(from)
                order.add(target.coerceIn(0, order.size), key)
                listener?.onLayoutChanged()
            }
        }
        clearOverlays()
        requestLayout()
        post { if (editMode) buildOverlays() }
    }

    /**
     * 手指落在哪张卡上，就把被拖的那张插到它所在的序号。
     *
     * 只认「手指确实在另一张卡的矩形里」：之前按最近的一张算，手抖几个像素就会
     * 和邻居换位，也容易和没动过混淆。落在缝里就当没动。
     */
    private fun dropIndexAt(x: Float, y: Float, draggedKey: String): Int {
        val row = (y - paddingTop).toInt()
        val hit = slots.firstOrNull { slot ->
            slot.key != draggedKey && slot.bounds.contains(x.toInt(), row)
        } ?: return -1
        return order.indexOf(hit.key)
    }

    // ---------------------------------------------------------------- 手动缩放

    private fun beginResize(key: String, x: Float, y: Float) {
        val size = sizeOf.getOrPut(key) { minSizeOf[key]?.copyOf() ?: intArrayOf(1, 1) }
        resizeKey = key
        resizeStartW = size[0]
        resizeStartH = size[1]
        resizeDownX = x
        resizeDownY = y
    }

    /**
     * 拖动右下角手柄时实时改尺寸。
     *
     * 网格是整数格子的，不存在「像素级自由大小」，所以按格子吸附：
     * 横向过一档的一半就跨 1→2 档，纵向每拉过一行高度就多占一行（最多三行）。
     * 吸附是立刻到位的，没有回弹动效。
     */
    private fun applyResize(x: Float, y: Float) {
        val key = resizeKey ?: return
        val current = sizeOf.getOrPut(key) { intArrayOf(1, 1) }
        val minW = minSizeOf[key]?.get(0) ?: 1
        val minH = minSizeOf[key]?.get(1) ?: 1
        val dx = x - resizeDownX
        val dy = y - resizeDownY
        val threshold = unitWidth * 0.55f

        val targetW = when {
            resizeStartW == 1 && dx > threshold -> 2
            resizeStartW == 2 && dx < -threshold -> 1
            else -> resizeStartW
        }.coerceAtLeast(minW)

        val rowsDelta = (dy / unitHeight).roundToInt()
        val targetH = (resizeStartH + rowsDelta).coerceIn(minH, MAX_SPAN_H)

        if (current[0] != targetW || current[1] != targetH) {
            sizeOf[key] = intArrayOf(targetW, targetH)
            requestLayout()
        }
    }

    private fun finishResize() {
        val changed = resizeKey != null
        resizeKey = null
        if (changed) listener?.onLayoutChanged()
        clearOverlays()
        requestLayout()
        post { if (editMode) buildOverlays() }
    }

    private fun resizeHandleAt(x: Float, y: Float): String? {
        slots.forEachIndexed { index, slot ->
            val handle = overlays.getOrNull(index * 3 + 2) ?: return@forEachIndexed
            if (x >= handle.left && x <= handle.right && y >= handle.top && y <= handle.bottom) {
                return slot.key
            }
        }
        return null
    }

    private fun slotAt(x: Float, y: Float): Slot? =
        slots.firstOrNull { it.bounds.contains(x.toInt(), (y - paddingTop).toInt()) }

    private fun onOverlay(x: Float, y: Float): Boolean =
        overlays.any { child ->
            x >= child.left && x <= child.right && y >= child.top && y <= child.bottom
        }

    private fun Int.dp(): Int = (this * density).roundToInt()

    companion object {
        const val UNIT_DP = 84
        const val GAP_DP = 10
        const val TILE_CELL = 4
        const val MAX_SPAN_H = 3
        private const val OVERLAY_DP = 26
    }
}
