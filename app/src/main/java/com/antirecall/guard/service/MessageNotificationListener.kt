package com.antirecall.guard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.antirecall.guard.data.MessageDao
import com.antirecall.guard.data.MessageDatabase
import com.antirecall.guard.data.MessageEntity
import com.antirecall.guard.util.AppConstants
import kotlinx.coroutines.*

/**
 * 通知监听服务 - 备份捕获层
 *
 * 工作原理：
 * 1. 监听微信/QQ/钉钉的通知栏消息
 * 2. 当通知被移除时（可能是撤回），保留原始内容
 * 3. 与无障碍服务互补，覆盖通知场景
 */
class MessageNotificationListener : NotificationListenerService() {

    private val TAG = "NotificationListener"
    private lateinit var dao: MessageDao
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 缓存最近的通知，key = 通知ID */
    private val notificationCache = mutableMapOf<String, CachedNotification>()

    override fun onCreate() {
        super.onCreate()
        dao = MessageDatabase.getInstance(this).messageDao()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName
        if (packageName !in AppConstants.MONITORED_PACKAGES) return

        val appName = AppConstants.APP_NAME_MAP[packageName] ?: return

        try {
            val extras = sbn.notification.extras ?: return

            val title = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString() ?: text

            if (text.isBlank() && bigText.isBlank()) return

            // 排除撤回提示
            if (AppConstants.RECALL_PATTERNS.any { it.containsMatchIn(text) }) return

            val content = if (bigText.length > text.length) bigText else text
            val contentHash = md5("$packageName:$title:$content")

            // 缓存通知
            val cacheKey = "${sbn.packageName}:${sbn.id}:${sbn.tag}"
            notificationCache[cacheKey] = CachedNotification(
                key = cacheKey,
                packageName = packageName,
                appName = appName,
                sender = title,
                content = content,
                chatName = subText,
                timestamp = System.currentTimeMillis(),
                contentHash = contentHash
            )

            // 限制缓存大小
            if (notificationCache.size > 200) {
                val oldest = notificationCache.minByOrNull { it.value.timestamp }
                oldest?.let { notificationCache.remove(it.key) }
            }

            // 存入数据库
            serviceScope.launch {
                try {
                    val existing = dao.findByContentHash(contentHash, System.currentTimeMillis() - AppConstants.DEDUP_WINDOW_MS)
                    if (existing == null) {
                        dao.insert(
                            MessageEntity(
                                packageName = packageName,
                                appName = appName,
                                sender = title,
                                content = content,
                                timestamp = System.currentTimeMillis(),
                                chatName = subText.ifBlank { null },
                                contentHash = contentHash,
                                captureMethod = "notification"
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "保存通知消息出错", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理通知出错", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return

        val packageName = sbn.packageName
        if (packageName !in AppConstants.MONITORED_PACKAGES) return

        val cacheKey = "${sbn.packageName}:${sbn.id}:${sbn.tag}"
        val cached = notificationCache.remove(cacheKey) ?: return

        // 通知被移除 - 可能是用户手动删除，也可能是撤回
        // 我们标记为"可能被撤回"但不在通知栏确认，留给无障碍服务判断
        Log.d(TAG, "通知被移除: app=${cached.appName}, sender=${cached.sender}")

        // 如果该通知在短时间内被移除（<5秒），更可能是撤回
        val elapsed = System.currentTimeMillis() - cached.timestamp
        if (elapsed < 5000) {
            serviceScope.launch {
                try {
                    // 查看数据库中是否已有此消息且未被标记为撤回
                    val existing = dao.findByContentHash(cached.contentHash, cached.timestamp - 1000)
                    if (existing != null && !existing.isRecalled) {
                        // 短时间移除，可能是撤回，标记
                        val updated = existing.copy(
                            isRecalled = true,
                            recallTime = System.currentTimeMillis()
                        )
                        dao.update(updated)
                        Log.d(TAG, "短时间通知移除，标记为可能撤回: ${existing.content.take(30)}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理通知移除出错", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun md5(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class CachedNotification(
        val key: String,
        val packageName: String,
        val appName: String,
        val sender: String,
        val content: String,
        val chatName: String?,
        val timestamp: Long,
        val contentHash: String
    )
}
