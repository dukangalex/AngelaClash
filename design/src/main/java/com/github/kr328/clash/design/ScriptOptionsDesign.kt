package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.OnChangedListener
import com.github.kr328.clash.design.preference.Preference
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
        RestoreScript,
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
            val deps = mutableListOf<Preference>()
            val enabled = Flag(snap.enabled)

            switch(
                value = enabled::on,
                title = R.string.script_display_enable,
                summary = R.string.script_display_enable_summary,
            ) {
                listener = OnChangedListener {
                    store.setEnabled(enabled.on)
                    deps.forEach { it.enabled = enabled.on }
                }
            }

            if (running) {
                tips(R.string.script_display_running)
            }
            when (snap.compatible) {
                "bettbox" -> tips(R.string.script_display_bettbox)
                "angela" -> tips(R.string.script_display_angela)
                else -> tips(R.string.script_display_hint)
            }

            clickable(
                title = R.string.script_display_edit,
                summary = R.string.script_display_edit_summary,
            ) {
                clicked { requests.trySend(Request.EditScript) }
            }
            clickable(
                title = R.string.script_display_restore,
                summary = R.string.script_display_restore_summary,
            ) {
                clicked { requests.trySend(Request.RestoreScript) }
            }

            val titles = mapOf(
                ScriptDisplayStore.GROUP_RULE to R.string.script_display_rule,
                ScriptDisplayStore.GROUP_LEAK to R.string.script_display_leak,
                ScriptDisplayStore.GROUP_CN to R.string.script_display_cn,
                ScriptDisplayStore.GROUP_STRICT to R.string.script_display_strict,
            )
            for (section in snap.sections) {
                category(titles.getValue(section.group))
                for (row in section.rows) {
                    val flag = Flag(row.on)
                    val group = section.group
                    val name = row.name
                    val pref = switch(flag::on) {
                        title = name
                        summary = row.summary
                        listener = OnChangedListener {
                            store.setOption(group, name, flag.on)
                        }
                    }
                    deps.add(pref)
                }
            }

            if (!enabled.on) {
                deps.forEach { it.enabled = false }
            }
        }

        binding.content.addView(screen.root)
    }
}
