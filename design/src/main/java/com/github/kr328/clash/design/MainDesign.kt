package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenChain,
        OpenProviders,
        OpenLogs,
        OpenSettings,
        OpenApp,
        OpenEnhance,
        OpenHelp,
        OpenAbout,
        OpenScript,
        CheckUpdate,
        SetRuleMode,
        SetGlobalMode,
        SetDirectMode,
        ReloadConfig,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    private var suppressMode = false

    override val root: View
        get() = binding.root

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setChainSummary(summary: String?) {
        withContext(Dispatchers.Main) {
            binding.showChain = !summary.isNullOrBlank()
            binding.chainSummary = summary ?: ""
        }
    }

    fun setFeatureVisibility(script: Boolean, chain: Boolean) {
        binding.showScript = script
        binding.showChain = chain
    }

    fun mountSystem(store: ScriptDisplayStore) {
        store.ensureScript()
        val host = binding.explicitHost
        host.removeAllViews()
        val titles = mapOf(
            ScriptDisplayStore.GROUP_LEAK to R.string.script_display_leak,
            ScriptDisplayStore.GROUP_CN to R.string.script_display_cn,
            ScriptDisplayStore.GROUP_STRICT to R.string.script_display_strict,
            ScriptDisplayStore.GROUP_PRIVACY to R.string.script_display_privacy,
        )
        for (section in store.systemSections()) {
            host.addView(systemCard(store, section, context.getString(titles.getValue(section.group))))
        }
    }

    private fun systemCard(
        store: ScriptDisplayStore,
        section: ScriptDisplayStore.Section,
        title: String,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        val onCount = section.rows.count { it.on }
        val meta = TextView(context).apply {
            text = "$onCount/${section.rows.size}"
            setTextColor(context.resolveThemedColor(android.R.attr.textColorSecondary))
        }
        val label = TextView(context).apply {
            text = title
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(label)
            addView(meta)
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
            }
        }
        for (row in section.rows) {
            val summary = TextView(context).apply {
                text = row.summary ?: ""
                textSize = 12f
                setTextColor(context.resolveThemedColor(android.R.attr.textColorSecondary))
            }
            val titleView = TextView(context).apply {
                text = row.name
                textSize = 15f
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(titleView)
                if (!row.summary.isNullOrBlank()) addView(summary)
            }
            val toggle = SwitchCompat(context).apply {
                isChecked = row.on
                setOnCheckedChangeListener { _, checked ->
                    store.setOption(section.group, row.name, checked)
                    val fresh = store.systemSections().find { it.group == section.group }
                    val count = fresh?.rows?.count { it.on } ?: 0
                    val total = fresh?.rows?.size ?: section.rows.size
                    meta.text = "$count/$total"
                    request(Request.ReloadConfig)
                }
            }
            body.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(texts)
                addView(toggle)
            })
        }
        return MaterialCardView(context).apply {
            radius = 20 * density
            cardElevation = 0f
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = dp(10)
            layoutParams = params
            setCardBackgroundColor(context.resolveThemedColor(com.google.android.material.R.attr.colorSurface))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                addView(body)
            })
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashRunning = running
        }
    }

    suspend fun setTraffic(now: Long, total: Long) {
        withContext(Dispatchers.Main) {
            binding.upload = now.trafficUpload()
            binding.download = now.trafficDownload()
            binding.forwarded = total.trafficTotal()
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            suppressMode = true
            val id = when (mode) {
                TunnelState.Mode.Direct -> R.id.mode_direct
                TunnelState.Mode.Global -> R.id.mode_global
                else -> R.id.mode_rule
            }
            binding.modeGroup.check(id)
            paintMode(id)
            suppressMode = false
        }
    }

    private fun paintMode(selected: Int) {
        val primary = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val onPrimary = context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
        val normal = context.resolveThemedColor(android.R.attr.textColorPrimary)
        for (button in listOf(binding.modeRule, binding.modeGlobal, binding.modeDirect)) {
            val on = button.id == selected
            button.backgroundTintList = ColorStateList.valueOf(
                if (on) primary else android.graphics.Color.TRANSPARENT
            )
            button.setTextColor(if (on) onPrimary else normal)
            button.strokeWidth = 0
        }
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            binding.hasProviders = has
        }
    }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
            }

            AlertDialog.Builder(context)
                .setView(binding.root)
                .show()
        }
    }

    init {
        binding.self = this
        binding.pageTools = false
        binding.showScript = false
        binding.showChain = false
        binding.upload = "0 B"
        binding.download = "0 B"
        binding.forwarded = "0 B"

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)
        binding.chainSummary = ""

        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressMode) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.mode_global -> request(Request.SetGlobalMode)
                R.id.mode_direct -> request(Request.SetDirectMode)
                R.id.mode_rule -> request(Request.SetRuleMode)
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (UiStore(context).hapticFeedback) {
                binding.bottomNav.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            when (item.itemId) {
                R.id.nav_tools -> {
                    binding.pageTools = true
                    true
                }
                R.id.nav_dashboard -> {
                    binding.pageTools = false
                    true
                }
                R.id.nav_proxy -> {
                    request(Request.OpenProxy)
                    false
                }
                R.id.nav_profiles -> {
                    request(Request.OpenProfiles)
                    false
                }
                else -> false
            }
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
