package com.github.kr328.clash

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.addTextChangedListener
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
        val script = store.scriptText()
        val editorHeight = minOf(dp(360), (resources.displayMetrics.heightPixels * 0.42f).toInt())
            .coerceAtLeast(dp(220))

        val emptyState = android.widget.TextView(this).apply {
            text = getString(R.string.script_editor_empty_state)
            textSize = 13f
            setTextColor(resolveThemedColor(android.R.attr.textColorSecondary))
            visibility = if (script.isBlank()) View.VISIBLE else View.GONE
            setPadding(dp(4), 0, dp(4), dp(10))
        }

        val input = EditText(this).apply {
            setText(script)
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 13f
            includeFontPadding = false
            hint = getString(R.string.script_editor_placeholder)
            setTextColor(resolveThemedColor(android.R.attr.textColorPrimary))
            setHintTextColor(resolveThemedColor(android.R.attr.textColorSecondary))
            minLines = 10
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            isVerticalScrollBarEnabled = true
            setLineSpacing(dp(2).toFloat(), 1f)
            background = null
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addTextChangedListener { value ->
                emptyState.visibility = if (value.isNullOrBlank()) View.VISIBLE else View.GONE
            }
        }
        val editorCard = MaterialCardView(this).apply {
            radius = 16f * density
            cardElevation = 0f
            val surface = resolveThemedColor(R.attr.clashSurfaceVariant)
            setCardBackgroundColor(surface)
            setStrokeColor(resolveThemedColor(R.attr.clashOutline))
            strokeWidth = dp(1)
            addView(FrameLayout(this@ScriptOptionsActivity).apply {
                addView(input, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                editorHeight,
            ))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(emptyState, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(editorCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(content, FrameLayout.LayoutParams(
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
