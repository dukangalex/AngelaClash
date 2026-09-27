package com.github.kr328.clash

import android.graphics.Typeface
import android.view.Gravity
import android.widget.EditText
import android.widget.ScrollView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ScriptOptionsDesign
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.util.withProfile
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
                        ScriptOptionsDesign.Request.RestoreScript -> confirmRestore(store)
                    }
                }
            }
        }
    }

    private fun showEditor(store: ScriptDisplayStore) {
        val input = EditText(this).apply {
            setText(store.scriptText())
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 12f
            minLines = 12
            maxHeight = (resources.displayMetrics.heightPixels * 0.5f).toInt()
            setPadding(48, 24, 48, 24)
        }
        val scroll = ScrollView(this).apply { addView(input) }
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

    private fun confirmRestore(store: ScriptDisplayStore) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.script_display_restore)
            .setMessage(R.string.script_display_restore_message)
            .setPositiveButton(R.string.ok) { _, _ ->
                store.restoreDefault()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
