package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.OnChangedListener
import com.github.kr328.clash.design.preference.category
import com.github.kr328.clash.design.preference.clickable
import com.github.kr328.clash.design.preference.preferenceScreen
import com.github.kr328.clash.design.preference.switch
import com.github.kr328.clash.design.preference.tips
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ScriptDisplayStore

class ScriptOptionsDesign(
    context: Context,
    store: ScriptDisplayStore,
    running: Boolean,
) : Design<ScriptOptionsDesign.Request>(context) {
    enum class Request {
        EditScript,
        Reload,
    }

    private class Flag(var on: Boolean)

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val snap = store.snapshot()
        val screen = preferenceScreen(context) {
            val enabled = Flag(snap.scriptEnabled)

            switch(
                value = enabled::on,
                title = R.string.script_enable,
                summary = R.string.script_enable_summary,
            ) {
                listener = OnChangedListener {
                    store.setScriptEnabled(enabled.on)
                    requests.trySend(Request.Reload)
                }
            }

            if (running) {
                tips(R.string.script_display_running)
            }
            tips(R.string.script_page_hint)

            clickable(
                title = R.string.script_display_edit,
                summary = R.string.script_display_edit_summary,
            ) {
                clicked { requests.trySend(Request.EditScript) }
            }

            if (snap.sections.isNotEmpty() && snap.sections[0].rows.isNotEmpty()) {
                category(R.string.script_display_rule)
                for (row in snap.sections[0].rows) {
                    val flag = Flag(row.on)
                    val name = row.name
                    switch(flag::on) {
                        title = name
                        summary = row.summary
                        listener = OnChangedListener {
                            store.setOption(ScriptDisplayStore.GROUP_RULE, name, flag.on)
                            requests.trySend(Request.Reload)
                        }
                    }
                }
            }
        }

        binding.content.addView(screen.root)
    }
}
