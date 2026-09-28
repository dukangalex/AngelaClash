package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.startClashService

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                sync(context)
                val store = ServiceStore(context)
                val resume = store.autoRestart && StatusProvider.shouldStartClashOnBoot
                if (store.startOnBoot || resume)
                    context.startClashService()
            }
        }
    }

    companion object {
        fun sync(context: Context) {
            val store = ServiceStore(context)
            if (!store.autoRestartMigrated) {
                val status = context.packageManager.getComponentEnabledSetting(
                    RestartReceiver::class.componentName
                )
                store.autoRestart = status == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                store.autoRestartMigrated = true
            }
            val enable = store.autoRestart || store.startOnBoot
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, RestartReceiver::class.java),
                if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}