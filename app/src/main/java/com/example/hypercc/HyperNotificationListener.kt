package com.example.hypercc

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Menerima notifikasi sebenar daripada sistem (selepas pengguna beri Notification access).
 * UI melanggan perubahan melalui [addListener] supaya senarai sentiasa terkini.
 */
class HyperNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        connected = true
        reload(null)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        connected = false
        cache = emptyList()
        fire()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = reload(null)

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = reload(sbn?.key)

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = reload(null)

    private fun reload(removedKey: String?) {
        val active = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()
        cache = active.filter { it.packageName != packageName && it.key != removedKey }
        fire()
    }

    companion object {
        @Volatile var instance: HyperNotificationListener? = null
        @Volatile var connected = false
        @Volatile private var cache: List<StatusBarNotification> = emptyList()
        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        private val main = Handler(Looper.getMainLooper())

        fun snapshot(): List<StatusBarNotification> = cache

        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }

        private fun fire() { main.post { listeners.forEach { it() } } }

        fun dismiss(key: String) { runCatching { instance?.cancelNotification(key) } }

        fun dismissAll() { runCatching { instance?.cancelAllNotifications() } }

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
        }

        /** Sesetengah ROM (Vivo, dll.) putuskan listener di latar; minta sambung semula. */
        fun ensureBound(ctx: Context) {
            if (isEnabled(ctx) && !connected) {
                runCatching {
                    NotificationListenerService.requestRebind(ComponentName(ctx, HyperNotificationListener::class.java))
                }
            }
        }
    }
}
