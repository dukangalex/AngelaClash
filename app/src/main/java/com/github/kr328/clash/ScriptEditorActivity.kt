package com.github.kr328.clash

import android.os.Bundle
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ScriptEditorDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.util.withProfile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class ScriptEditorActivity : BaseActivity<ScriptEditorDesign>() {
    private var restoredDraft: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        restoredDraft = savedInstanceState?.getString(STATE_DRAFT)
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        design?.let { outState.putString(STATE_DRAFT, it.scriptText()) }
        super.onSaveInstanceState(outState)
    }

    override suspend fun main() {
        title = getString(R.string.script_display_edit)
        val store = ScriptDisplayStore(this)
        val savedScript = store.scriptText()
        val editor = ScriptEditorDesign(this, savedScript, restoredDraft ?: savedScript)
        setContentDesign(editor)

        while (isActive) {
            select<Unit> {
                events.onReceive { }
                editor.requests.onReceive {
                    when (it) {
                        ScriptEditorDesign.Request.Save -> save(store, editor)
                    }
                }
            }
        }
    }

    private suspend fun save(store: ScriptDisplayStore, editor: ScriptEditorDesign) {
        if (!editor.hasUnsavedChanges()) return

        store.writeScript(editor.scriptText())
        editor.markSaved()

        val active = if (clashRunning) withProfile { queryActive() } else null
        if (active != null && clashRunning) {
            sendProfileChanged(active.uuid)
            editor.showToast(R.string.script_applied, ToastDuration.Short)
        } else {
            editor.showToast(R.string.script_editor_saved_toast, ToastDuration.Short)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val editor = design
        if (editor?.hasUnsavedChanges() != true) {
            super.onBackPressed()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.script_editor_unsaved_title)
            .setMessage(R.string.script_editor_unsaved_message)
            .setNegativeButton(R.string.keep_editing, null)
            .setPositiveButton(R.string.script_editor_discard) { _, _ -> finish() }
            .show()
    }

    companion object {
        private const val STATE_DRAFT = "script-editor-draft"
    }
}
