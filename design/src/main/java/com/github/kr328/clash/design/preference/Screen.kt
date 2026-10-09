package com.github.kr328.clash.design.preference

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope

interface PreferenceScreen : CoroutineScope {
    val context: android.content.Context
    val root: ViewGroup
}

const val PREF_OUTSIDE = "pref_outside"

fun CoroutineScope.preferenceScreen(
    context: android.content.Context,
    configure: PreferenceScreen.() -> Unit
): PreferenceScreen {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    val impl = object : PreferenceScreen, CoroutineScope by this {
        override val context: android.content.Context
            get() = context
        override val root: ViewGroup
            get() = root
    }

    impl.configure()

    return impl
}

fun PreferenceScreen.addElement(preference: Preference) {
    val view = preference.view
    val density = root.resources.displayMetrics.density
    fun dp(n: Int) = (n * density).toInt()

    if (view.tag == PREF_OUTSIDE) {
        root.tag = null
        val params = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        params.setMargins(dp(20), dp(12), dp(16), 0)
        root.addView(view, params)
        return
    }

    val box = root.tag as? LinearLayout ?: LinearLayout(root.context).also { inner ->
        inner.orientation = LinearLayout.VERTICAL
        val card = MaterialCardView(root.context).apply {
            radius = 20 * density
            cardElevation = 0f
            setCardBackgroundColor(
                root.context.resolveThemedColor(com.github.kr328.clash.design.R.attr.clashSurfaceVariant)
            )
            addView(inner, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val params = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        params.setMargins(dp(16), dp(6), dp(16), dp(8))
        root.addView(card, params)
        root.tag = inner
    }

    if (box.childCount > 0) {
        val divider = View(root.context).apply {
            setBackgroundColor(root.context.resolveThemedColor(com.github.kr328.clash.design.R.attr.clashOutline))
        }
        val dividerParams = LayoutParams(MATCH_PARENT, dp(1)).apply {
            setMargins(dp(16), 0, dp(16), 0)
        }
        box.addView(divider, dividerParams)
    }
    box.addView(view, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}
