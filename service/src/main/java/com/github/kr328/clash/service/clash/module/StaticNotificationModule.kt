package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.store.ScriptDisplayStore
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.channels.Channel

class StaticNotificationModule(service: Service) : Module<Unit>(service) {
    private val channel = channelId(service)
    private val builder = NotificationCompat.Builder(service, channel)
        .setSmallIcon(R.drawable.ic_logo_service)
        .setOngoing(true)
        .setColor(service.getColorCompat(R.color.color_clash))
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                service,
                R.id.nf_clash_status,
                Intent().setComponent(Components.MAIN_ACTIVITY)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        )

    override suspend fun run() {
        val loaded = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
        }

        while (true) {
            loaded.receive()

            val profileName = if (hideProfile(service)) {
                service.getString(R.string.running)
            } else {
                StatusProvider.currentProfile ?: service.getString(R.string.running)
            }

            val notification = builder
                .setContentTitle(profileName)
                .setContentText(service.getText(R.string.running))
                .build()

            service.startForegroundCompat(R.id.nf_clash_status, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "clash_status_channel"
        const val CHANNEL_HIGH = "clash_status_channel_high"

        fun channelId(service: Service): String {
            return if (ServiceStore(service).highPriorityNotification) CHANNEL_HIGH else CHANNEL_ID
        }

        private fun hideProfile(service: Service): Boolean {
            return ScriptDisplayStore(service).optionOn(
                ScriptDisplayStore.GROUP_PRIVACY,
                "隐藏订阅名",
                false,
            )
        }

        fun createNotificationChannel(service: Service) {
            val high = ServiceStore(service).highPriorityNotification
            NotificationManagerCompat.from(service).createNotificationChannel(
                NotificationChannelCompat.Builder(
                    if (high) CHANNEL_HIGH else CHANNEL_ID,
                    if (high) NotificationManagerCompat.IMPORTANCE_DEFAULT
                    else NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(service.getText(R.string.clash_service_status_channel)).build()
            )
        }

        fun notifyLoadingNotification(service: Service) {
            val notification =
                NotificationCompat.Builder(service, channelId(service))
                    .setSmallIcon(R.drawable.ic_logo_service)
                    .setOngoing(true)
                    .setColor(service.getColorCompat(R.color.color_clash))
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setContentTitle(service.getText(R.string.loading))
                    .build()

            service.startForegroundCompat(R.id.nf_clash_status, notification)
        }
    }
}