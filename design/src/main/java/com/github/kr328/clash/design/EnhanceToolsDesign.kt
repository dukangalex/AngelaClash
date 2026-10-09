package com.github.kr328.clash.design

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.core.content.getSystemService
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ServiceStore

class EnhanceToolsDesign(
    context: Context,
    srvStore: ServiceStore,
) : Design<Unit>(context) {
    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            switch(
                value = srvStore::storeFix,
                title = R.string.store_fix,
                summary = R.string.store_fix_summary,
            )

            switch(
                value = srvStore::highPriorityNotification,
                title = R.string.high_priority_notification,
                summary = R.string.high_priority_notification_summary,
            )

            val pm = context.getSystemService<PowerManager>()
            val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            clickable(
                title = R.string.battery_optimization,
                summary = if (ignoring) R.string.battery_optimization_ignored_summary else R.string.battery_optimization_summary,
            ) {
                clicked {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}"))
                    runCatching { context.startActivity(intent) }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
        }

        binding.content.addView(screen.root)
    }
}
