package com.github.kr328.clash

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ScriptOptionsDesign
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.util.withProfile
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class ScriptOptionsActivity : BaseActivity<ScriptOptionsDesign>() {
    override suspend fun main() {
        val store = ScriptDisplayStore(this)
        val design = ScriptOptionsDesign(this, store, clashRunning)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ScriptOptionsDesign.Request.EditScript -> showEditor(store)
                        ScriptOptionsDesign.Request.Reload -> {
                            val active = withProfile { queryActive() }
                            if (active != null && clashRunning) sendProfileChanged(active.uuid)
                        }
                    }
                }
            }
        }
    }

    private fun showEditor(store: ScriptDisplayStore) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val input = EditText(this).apply {
            setText(store.scriptText())
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 13f
            hint = this@ScriptOptionsActivity.getString(R.string.script_editor_placeholder)
            setTextColor(this@ScriptOptionsActivity.resolveThemedColor(android.R.attr.textColorPrimary))
            setHintTextColor(this@ScriptOptionsActivity.resolveThemedColor(android.R.attr.textColorSecondary))
            minLines = 14
            maxHeight = (resources.displayMetrics.heightPixels * 0.52f).toInt()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
            setLineSpacing(dp(2).toFloat(), 1f)
            background = null
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val editorCard = MaterialCardView(this).apply {
            radius = 16f * density
            cardElevation = 0f
            val surface = this@ScriptOptionsActivity.resolveThemedColor(com.google.android.material.R.attr.colorSurface)
            setCardBackgroundColor(surface)
            val secondary = this@ScriptOptionsActivity.resolveThemedColor(android.R.attr.textColorSecondary)
            val alpha = if (ColorUtils.calculateLuminance(surface) < 0.5) 0.25f else 0.45f
            setStrokeColor(ColorUtils.setAlphaComponent(secondary, (Color.alpha(secondary) * alpha).toInt()))
            strokeWidth = dp(1)
            addView(input, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(editorCard, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.script_display_edit)
            .setView(scroll)
            .setPositiveButton(R.string.save) { _, _ ->
                store.writeScript(input.text?.toString().orEmpty())
                launch {
                    val active = withProfile { queryActive() }
                    if (active != null && clashRunning) {
                        sendProfileChanged(active.uuid)
                        design?.showToast(R.string.script_applied, ToastDuration.Short)
                    }
                    recreate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
