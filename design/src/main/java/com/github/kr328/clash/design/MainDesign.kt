package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
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
        OpenHelp,
        OpenAbout,
        OpenScript,
        OpenAccess,
        CheckUpdate,
        SetRuleMode,
        SetGlobalMode,
        SetDirectMode,
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
            binding.chainSummary = summary ?: context.getString(R.string.chain_not_set)
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashRunning = running
            val color = if (running) binding.colorClashStarted else binding.colorClashStopped
            binding.power.backgroundTintList = ColorStateList.valueOf(color)
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
            suppressMode = false
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
        binding.upload = "0 B"
        binding.download = "0 B"
        binding.forwarded = "0 B"

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)
        binding.chainSummary = context.getString(R.string.chain_not_set)
        binding.power.backgroundTintList = ColorStateList.valueOf(binding.colorClashStopped)

        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressMode) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.mode_global -> request(Request.SetGlobalMode)
                R.id.mode_direct -> request(Request.SetDirectMode)
                R.id.mode_rule -> request(Request.SetRuleMode)
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
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
