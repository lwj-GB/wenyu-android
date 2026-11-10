package app.resound.diary

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Android 授权范围较宽；此服务只读取去重标识、包名、发布时间和通知类型标记。 */
class MetadataNotificationListener : NotificationListenerService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val store by lazy { NotificationMetadataStore(this) }
    private fun enqueue(action: () -> Unit) {
        executor.execute { runCatching(action).onFailure { store.recordError() } }
    }
    override fun onListenerConnected() {
        val at = System.currentTimeMillis(); enqueue { store.connected(at) }
    }
    override fun onListenerDisconnected() {
        val at = System.currentTimeMillis(); enqueue { store.disconnected(at) }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 不读取 notification.extras、标题、正文、联系人或 action，也不扫描当前通知历史。
        if (sbn.notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        val identity = notificationIdentityHash(sbn.key)
        val pkg = sbn.packageName
        val postedAt = sbn.postTime
        val receivedAt = System.currentTimeMillis()
        enqueue { store.posted(pkg, identity, postedAt, receivedAt) }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val identity = notificationIdentityHash(sbn.key)
        val at = System.currentTimeMillis(); enqueue { store.removed(identity, at) }
    }
    override fun onDestroy() {
        val at = System.currentTimeMillis(); enqueue { store.disconnected(at) }
        executor.shutdown(); super.onDestroy()
    }
}

internal fun notificationIdentityHash(identity: String): String = MessageDigest.getInstance("SHA-256")
    .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
