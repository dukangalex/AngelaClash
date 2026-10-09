package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.core.widget.addTextChangedListener
import com.github.kr328.clash.design.databinding.DesignScriptEditorBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root

class ScriptEditorDesign(
    context: Context,
    initialScript: String,
    draftScript: String = initialScript,
) : Design<ScriptEditorDesign.Request>(context) {
    enum class Request {
        Save,
    }

    private val binding = DesignScriptEditorBinding
        .inflate(context.layoutInflater, context.root, false)
    private var savedText = initialScript

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.surface = surface
        binding.dirty = draftScript != savedText
        binding.empty = draftScript.isBlank()
        binding.activityBarLayout.applyFrom(context)
        binding.scriptInput.setText(draftScript)
        binding.scriptInput.setSelection(binding.scriptInput.text.length)
        updateStatusColor(binding.dirty)
        binding.scriptInput.addTextChangedListener { text ->
            binding.dirty = text?.toString().orEmpty() != savedText
            binding.empty = text.isNullOrBlank()
            updateStatusColor(binding.dirty)
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    fun scriptText(): String = binding.scriptInput.text?.toString().orEmpty()

    fun hasUnsavedChanges(): Boolean = scriptText() != savedText

    fun markSaved() {
        savedText = scriptText()
        binding.dirty = false
        updateStatusColor(false)
    }

    private fun updateStatusColor(dirty: Boolean) {
        binding.editorStatus.setTextColor(
            context.resolveThemedColor(
                if (dirty) com.google.android.material.R.attr.colorPrimary
                else android.R.attr.textColorSecondary,
            ),
        )
    }
}
