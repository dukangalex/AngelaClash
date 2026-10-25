package com.github.kr328.clash.design.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * 首页网速面积图（参考 BettBox 首页那块大图）。
 *
 * 刻意做成「零动效」：每次数据到了直接重绘，不做任何插值/过渡动画，
 * 符合本项目「不要进场动画」的约定。
 *
 * 数据是两个环形缓冲：下行、上行各 [SAMPLES] 个点，值域 0..1（已经在外部归一化）。
 */
class SpeedChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val SAMPLES = 60
    }

    private val down = FloatArray(SAMPLES)
    private val up = FloatArray(SAMPLES)
    private var cursor = 0
    private var running = false

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#1A7C8BFF")
    }
    private val downLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val upLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val downFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val upFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val downPath = Path()
    private val upPath = Path()
    private val downArea = Path()
    private val upArea = Path()

    private val accent = Color.parseColor("#5B8CFF")
    private val accent2 = Color.parseColor("#7C5BFF")
    private val ok = Color.parseColor("#34D399")

    init {
        downLine.color = accent
        upLine.color = ok
    }

    /** 推一个采样点进去；值已经归一化到 0..1。 */
    fun push(downNorm: Float, upNorm: Float, running: Boolean) {
        this.running = running
        down[cursor] = downNorm.coerceIn(0f, 1f)
        up[cursor] = upNorm.coerceIn(0f, 1f)
        cursor = (cursor + 1) % SAMPLES
        invalidate()
    }

    /**
     * 只切换运行状态，不写入采样点。
     * （早期版本这里误用 push(0,0)，结果每次开关机都会在图尾压出一个假谷底。）
     */
    fun setRunning(running: Boolean) {
        if (this.running == running) return
        this.running = running
        rebuildShaders()
        invalidate()
    }

    fun reset() {
        down.fill(0f)
        up.fill(0f)
        cursor = 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 面积渐变依赖尺寸，尺寸变了就重建 shader
        rebuildShaders()
    }

    private fun rebuildShaders() {
        val h = height.toFloat().coerceAtLeast(1f)
        val visible = if (running) 1f else 0.45f
        downFill.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.argb((72 * visible).toInt(), 91, 140, 255),
            Color.argb(0, 91, 140, 255),
            Shader.TileMode.CLAMP,
        )
        upFill.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.argb((44 * visible).toInt(), 52, 211, 153),
            Color.argb(0, 52, 211, 153),
            Shader.TileMode.CLAMP,
        )
        downLine.alpha = if (running) 255 else 120
        upLine.alpha = if (running) 200 else 90
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 三条参考线，够用就好，不要网格铺满
        val g = h / 4f
        for (i in 1..3) {
            canvas.drawLine(0f, g * i * 2f - g, w, g * i * 2f - g, gridPaint)
        }

        buildPath(down, downPath, downArea, w, h, 0.92f)
        buildPath(up, upPath, upArea, w, h, 0.92f)

        canvas.drawPath(downArea, downFill)
        canvas.drawPath(upArea, upFill)
        canvas.drawPath(downPath, downLine)
        canvas.drawPath(upPath, upLine)
    }

    /**
     * 从环形缓冲生成折线；最新的点画在最右侧。
     * 平滑用简单的三次贝塞尔中点法，不引入额外依赖。
     */
    private fun buildPath(data: FloatArray, line: Path, area: Path, w: Float, h: Float, scale: Float) {
        line.reset()
        area.reset()

        val stepX = w / (SAMPLES - 1).toFloat()
        val top = h * (1f - scale)
        var prevX = 0f
        var prevY = 0f

        for (i in 0 until SAMPLES) {
            val v = data[(cursor + i) % SAMPLES]
            val x = stepX * i
            // 给一点最小高度，避免全是平的贴底
            val y = h - (v * top).coerceAtLeast(h * 0.02f)
            if (i == 0) {
                line.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
                prevX = x
                prevY = y
            } else {
                val midX = (prevX + x) / 2f
                line.quadTo(prevX, prevY, midX, (prevY + y) / 2f)
                area.quadTo(prevX, prevY, midX, (prevY + y) / 2f)
                line.lineTo(x, y)
                area.lineTo(x, y)
                prevX = x
                prevY = y
            }
        }

        area.lineTo(w, h)
        area.close()
    }
}
