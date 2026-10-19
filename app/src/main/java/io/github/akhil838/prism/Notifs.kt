package io.github.akhil838.prism

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Other apps' notifications for the Home control strip. Needs notification access (Android
 * Settings → Notification access, or once over ADB: cmd notification allow_listener …).
 */
object Notifs {
    class Item(
        val key: String,
        val pkg: String,
        val title: String,
        val text: String?,
        val time: Long,
        val intent: PendingIntent?,
        val clearable: Boolean,
    )

    var items: List<Item> = emptyList()
        private set
    private val listeners = LinkedHashSet<() -> Unit>()

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    fun component(ctx: Context) = ComponentName(ctx, NotificationService::class.java)

    fun granted(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(ctx))

    fun clearAll() = NotificationService.instance?.cancelAllNotifications()
    fun dismiss(item: Item) = NotificationService.instance?.cancelNotification(item.key)

    /** Listener callbacks arrive on the main thread. */
    internal fun update(sbns: Array<StatusBarNotification>?, self: String) {
        items = sbns.orEmpty()
            .filter { it.packageName != self && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .mapNotNull { sbn ->
                val x = sbn.notification.extras
                val title = (x.getCharSequence(Notification.EXTRA_TITLE) ?: x.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString()
                Item(sbn.key, sbn.packageName, title, text?.takeIf { it.isNotBlank() }, sbn.postTime, sbn.notification.contentIntent, sbn.isClearable)
            }
            .sortedByDescending { it.time }
        listeners.toList().forEach { it() }
    }
}

class NotificationService : NotificationListenerService() {
    override fun onListenerConnected() { instance = this; refresh() }
    override fun onListenerDisconnected() { instance = null; Notifs.update(null, packageName) }
    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() = Notifs.update(runCatching { activeNotifications }.getOrNull(), packageName)

    companion object {
        @Volatile var instance: NotificationService? = null
    }
}
