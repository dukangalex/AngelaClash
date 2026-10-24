package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.store.UiStore

class ProxyView(
    context: Context,
    config: ProxyViewConfig,
) : View(context) {

    init {
        background = context.getDrawableCompat(config.clickableBackground)
    }

    var state: ProxyViewState? = null
    constructor(context: Context) : this(context, ProxyViewConfig(context, 2))
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val state = state ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                resources.displayMetrics.widthPixels
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                MeasureSpec.getSize(widthMeasureSpec)
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        state.paint.apply {
            reset()

            textSize = state.config.textSize

            getTextBounds("Stub!", 0, 1, state.rect)
        }

        val textHeight = state.rect.height()
        val exceptHeight = (state.config.layoutPadding * 2 +
                state.config.contentPadding * 2 +
                textHeight * 2 +
                state.config.textMargin).toInt()

        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                exceptHeight
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                exceptHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        setMeasuredDimension(width, height)
    }

    override fun draw(canvas: Canvas) {
        val state = state ?: return super.draw(canvas)

        if (state.update(false))
            postInvalidate()

        val width = width.toFloat()
        val height = height.toFloat()

        val paint = state.paint

        paint.reset()

        paint.color = state.background
        paint.style = Paint.Style.FILL

        // draw background
        canvas.apply {
            val path = state.path
            path.reset()
            path.addRoundRect(
                state.config.layoutPadding,
                state.config.layoutPadding,
                width - state.config.layoutPadding,
                height - state.config.layoutPadding,
                state.config.cardRadius,
                state.config.cardRadius,
                Path.Direction.CW,
            )
            drawPath(path, paint)

            paint.style = Paint.Style.STROKE
            paint.isAntiAlias = true
            paint.color = if (state.isSelected) state.config.selectedControl else state.config.outlineColor
            paint.strokeWidth = (if (state.isSelected) 1.6f else 1.0f) * state.config.density
            drawPath(path, paint)

            clipPath(path)
        }

        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val state = state ?: return

        val paint = state.paint

        val width = width.toFloat()
        val height = height.toFloat()

        val delayTextSize = state.config.textSize * 0.86f
        paint.textSize = delayTextSize
        paint.isFakeBoldText = true

        // measure delay text bounds
        val delayCount = paint.breakText(
            state.delayText,
            false,
            (width - state.config.layoutPadding * 2 - state.config.contentPadding * 2)
                .coerceAtLeast(0f),
            null
        )

        state.paint.getTextBounds(state.delayText, 0, delayCount, state.rect)

        val delayPadH = if (delayCount > 0) 6f * state.config.density else 0f
        val delayPadV = if (delayCount > 0) 3.5f * state.config.density else 0f
        val delayWidth = if (delayCount > 0) state.rect.width() + delayPadH * 2 else 0f

        paint.textSize = state.config.textSize
        val mainTextWidth = (width -
                state.config.layoutPadding * 2 -
                state.config.contentPadding * 2 -
                delayWidth -
                state.config.textMargin * 2
                )
            .coerceAtLeast(0f)

        // measure title text bounds
        val titleCount = paint.breakText(
            state.title,
            false,
            mainTextWidth,
            null,
        )

        // measure subtitle text bounds
        paint.textSize = state.config.textSize * 0.86f
        paint.isFakeBoldText = false
        val subtitleCount = paint.breakText(
            state.subtitle,
            false,
            mainTextWidth,
            null,
        )

        // draw delay pill & text
        if (delayCount > 0) {
            paint.reset()
            paint.isAntiAlias = true
            paint.textSize = delayTextSize
            paint.isFakeBoldText = true
            val delayOffset = (paint.descent() + paint.ascent()) / 2
            val textX = width - state.config.layoutPadding - state.config.contentPadding - state.rect.width() - delayPadH
            val textY = height / 2f - delayOffset

            val pillLeft = textX - delayPadH
            val pillRight = textX + state.rect.width() + delayPadH
            val pillTop = height / 2f - state.rect.height() / 2f - delayPadV
            val pillBottom = height / 2f + state.rect.height() / 2f + delayPadV
            val pillRadius = (pillBottom - pillTop) / 2f

            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.argb(
                30,
                android.graphics.Color.red(state.delayColor),
                android.graphics.Color.green(state.delayColor),
                android.graphics.Color.blue(state.delayColor),
            )
            canvas.drawRoundRect(pillLeft, pillTop, pillRight, pillBottom, pillRadius, pillRadius, paint)

            paint.color = state.delayColor
            canvas.drawText(state.delayText, 0, delayCount, textX, textY, paint)
        }

        // draw title
        paint.reset()
        paint.textSize = state.config.textSize
        paint.isAntiAlias = true
        paint.isFakeBoldText = true
        paint.color = state.controls
        val titleOffset = (paint.descent() + paint.ascent()) / 2
        canvas.apply {
            val x = state.config.layoutPadding + state.config.contentPadding
            val y = state.config.layoutPadding +
                    (height - state.config.layoutPadding * 2) * 0.36f - titleOffset

            drawText(state.title, 0, titleCount, x, y, paint)
        }

        // draw subtitle
        paint.reset()
        paint.textSize = state.config.textSize * 0.86f
        paint.isAntiAlias = true
        paint.isFakeBoldText = false
        paint.color = state.controls
        paint.alpha = if (state.isSelected) 220 else 165
        val subOffset = (paint.descent() + paint.ascent()) / 2
        canvas.apply {
            val x = state.config.layoutPadding + state.config.contentPadding
            val y = state.config.layoutPadding +
                    (height - state.config.layoutPadding * 2) * 0.68f - subOffset

            drawText(state.subtitle, 0, subtitleCount, x, y, paint)
        }
    }
}