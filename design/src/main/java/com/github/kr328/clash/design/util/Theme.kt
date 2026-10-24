package com.github.kr328.clash.design.util

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.R

interface ClickableScope {
    fun focusable(defaultValue: Boolean): Boolean
    fun clickable(defaultValue: Boolean): Boolean
    fun background(): Drawable?
    fun foreground(): Drawable?
}

val Context.selectableItemBackground: Drawable?
    get() {
        return getDrawableCompat(resolveThemedResourceId(android.R.attr.selectableItemBackground))
    }

fun Context.resolveClickableAttrs(
    attributeSet: AttributeSet?,
    @AttrRes defaultAttrRes: Int = 0,
    @StyleRes defaultStyleRes: Int = 0,
    block: ClickableScope.() -> Unit,
) {
    theme.obtainStyledAttributes(
        attributeSet,
        R.styleable.Clickable,
        defaultAttrRes,
        defaultStyleRes
    ).apply {
        val impl = object : ClickableScope {
            override fun focusable(defaultValue: Boolean): Boolean {
                return getBoolean(R.styleable.Clickable_android_focusable, defaultValue)
            }

            override fun clickable(defaultValue: Boolean): Boolean {
                return getBoolean(R.styleable.Clickable_android_clickable, defaultValue)
            }

            override fun background(): Drawable? {
                return getDrawable(R.styleable.Clickable_android_background)
            }

            override fun foreground(): Drawable? {
                return getDrawable(R.styleable.Clickable_android_focusable)
            }

        }

        impl.apply(block)

        recycle()
    }
}

fun Context.resolveThemedColor(@AttrRes resId: Int): Int {
    val ta = theme.obtainStyledAttributes(intArrayOf(resId))
    try {
        val csl = ta.getColorStateList(0)
        if (csl != null) {
            return csl.defaultColor
        }
        val directColor = ta.getColor(0, 0)
        if (directColor != 0) {
            return directColor
        }
    } catch (_: Exception) {
    } finally {
        ta.recycle()
    }

    val value = TypedValue()
    if (!theme.resolveAttribute(resId, value, true)) {
        return 0
    }

    if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
        return value.data
    }

    val targetResId = if (value.resourceId != 0) value.resourceId else value.data
    if (targetResId != 0) {
        try {
            ContextCompat.getColorStateList(this, targetResId)?.let { return it.defaultColor }
        } catch (_: Resources.NotFoundException) {
        }
        try {
            return ContextCompat.getColor(this, targetResId)
        } catch (_: Resources.NotFoundException) {
        }
    }

    return value.data
}

fun Context.resolveThemedBoolean(@AttrRes resId: Int): Boolean {
    return TypedValue().apply {
        theme.resolveAttribute(resId, this, true)
    }.data != 0
}

fun Context.resolveThemedResourceId(@AttrRes resId: Int): Int {
    return TypedValue().apply {
        theme.resolveAttribute(resId, this, true)
    }.resourceId
}
