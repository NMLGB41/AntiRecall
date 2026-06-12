package com.antirecall.guard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.antirecall.guard.MainActivity
import com.antirecall.guard.R
import com.antirecall.guard.data.MessageDao
import com.antirecall.guard.data.MessageDatabase
import com.antirecall.guard.data.MessageEntity
import com.antirecall.guard.util.AppConstants
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.LinkedList

/**
 * 防撤回无障碍服务 - 核心引擎
 *
 * 工作原理：
 * 1. 监控微信/QQ/钉钉的界面变化
 * 2. 捕获聊天窗口中出现的所有文本消息
 * 3. 检测"撤回了一条消息"文本
 * 4. 匹配并标记被撤回的消息，保留原始内容
 * 5. 发送通知提醒用户有消息被撤回
 */
class AntiRecallAccessibilityService : AccessibilityService() {

    private val TAG = "AntiRecallService"

    private lateinit var dao: MessageDao
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 当前聊天窗口的消息缓存，用于匹配撤回 */
    private val messageCache = LinkedList<MessageEntity>()

    /** 上一次捕获的全屏文本，用于增量检测 */
    private var lastCapturedTexts = mutableSetOf<String>()

    /** 上一次检测到的撤回文本集合 */
    private var lastRecallTexts = mutableSetOf<String>()

    /** 是否已初始化 */
    private var isInitialized = false

    // ===== 生命周期 =====

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "无障碍服务已连接")

        dao = MessageDatabase.getInstance(this).messageDao()
        isInitialized = true

        // 配置服务信息
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            // 只监控目标应用
            packageNames = AppConstants.MONITORED_PACKAGES.toTypedArray()
        }

        // 启动前台通知
        startForegroundNotification()

        // 保存服务运行状态
        getSharedPreferences(AppConstants.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(AppConstants.KEY_SERVICE_ENABLED, true)
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "无障碍服务已销毁")
        serviceScope.cancel()
        getSharedPreferences(AppConstants.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(AppConstants.KEY_SERVICE_ENABLED, false)
            .apply()
    }

    // ===== 事件处理 =====

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isInitialized || event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in AppConstants.MONITORED_PACKAGES) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                processWindowChange(event)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
    }

    // ===== 核心逻辑 =====

    /**
     * 处理窗口内容变化
     */
    private fun processWindowChange(event: AccessibilityEvent) {
        try {
            val rootNode = rootInActiveWindow ?: return
            val packageName = event.packageName?.toString() ?: return
            val appName = AppConstants.APP_NAME_MAP[packageName] ?: return

            // 1. 提取当前屏幕所有文本
            val currentTexts = mutableSetOf<String>()
            val textNodes = mutableListOf<TextNodeInfo>()
            collectTextNodes(rootNode, currentTexts, textNodes)

            // 2. 检测撤回事件
            val recallMatches = detectRecalls(currentTexts)

            // 3. 处理新撤回
            for (match in recallMatches) {
                if (match.recallText !in lastRecallTexts) {
                    handleRecallDetected(match, packageName, appName)
                }
            }

            // 4. 捕获新消息
            val newTexts = currentTexts - lastCapturedTexts
            for (text in newTexts) {
                // 排除撤回提示文本本身
                if (!isRecallText(text) && text.isNotBlank() && text.length >= 2) {
                    captureMessage(text, packageName, appName, textNodes)
                }
            }

            // 5. 更新缓存状态
            lastCapturedTexts = currentTexts
            lastRecallTexts = recallMatches.map { it.recallText }.toMutableSet()

            // 6. 修剪缓存
            trimCache()

            rootNode.recycle()

        } catch (e: Exception) {
            Log.e(TAG, "处理窗口变化出错", e)
        }
    }

    /**
     * 递归收集所有文本节点
     */
    private fun collectTextNodes(
        node: AccessibilityNodeInfo,
        textSet: MutableSet<String>,
        textNodes: MutableList<TextNodeInfo>
    ) {
        try {
            val text = node.text?.toString()
            val contentDesc = node.contentDescription?.toString()

            if (!text.isNullOrBlank() && text.length in 1..2000) {
                textSet.add(text)
                // 尝试获取发送者（父节点的兄弟节点）
                val sender = findSenderName(node)
                textNodes.add(TextNodeInfo(text, sender, node.className?.toString() ?: ""))
            }

            if (!contentDesc.isNullOrBlank() && contentDesc.length in 1..2000) {
                textSet.add(contentDesc)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectTextNodes(child, textSet, textNodes)
                child.recycle()
            }
        } catch (e: Exception) {
            // 忽略单节点错误
        }
    }

    /**
     * 尝试从消息节点周围找到发送者名称
     */
    private fun findSenderName(node: AccessibilityNodeInfo): String {
        try {
            val parent = node.parent ?: return ""
            // 遍历兄弟节点找发送者
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue
                val siblingText = sibling.text?.toString()
                if (!siblingText.isNullOrBlank() && siblingText.length <= 30) {
                    // 发送者名称通常较短且在消息上方
                    if (siblingText != node.text?.toString()) {
                        sibling.recycle()
                        return siblingText
                    }
                }
                sibling.recycle()
            }
            parent.recycle()
        } catch (e: Exception) {
            // 忽略
        }
        return ""
    }

    /**
     * 检测撤回事件
     */
    private fun detectRecalls(currentTexts: Set<String>): List<RecallMatch> {
        val matches = mutableListOf<RecallMatch>()
        for (text in currentTexts) {
            for (pattern in AppConstants.RECALL_PATTERNS) {
                val match = pattern.find(text)
                if (match != null) {
                    val sender = match.groupValues[1].trim().removeSurrounding("\"")
                    matches.add(RecallMatch(recallText = text, sender = sender))
                    break
                }
            }
        }
        return matches
    }

    /**
     * 判断文本是否是撤回提示
     */
    private fun isRecallText(text: String): Boolean {
        return AppConstants.RECALL_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * 处理检测到的撤回
     */
    private fun handleRecallDetected(match: RecallMatch, packageName: String, appName: String) {
        Log.d(TAG, "检测到撤回: sender=${match.sender}, app=$appName")

        serviceScope.launch {
            // 在缓存中查找被撤回的消息
            val recalledMsg = findRecalledMessage(match.sender, packageName)

            if (recalledMsg != null) {
                // 标记为已撤回
                val updated = recalledMsg.copy(
                    isRecalled = true,
                    recallTime = System.currentTimeMillis()
                )
                dao.update(updated)
                Log.d(TAG, "已标记撤回消息: ${recalledMsg.content.take(50)}")

                // 发送通知
                showRecallNotification(updated)
            } else {
                // 没找到缓存消息，记录撤回事件本身
                Log.w(TAG, "未找到被撤回的原始消息: sender=${match.sender}")
            }
        }
    }

    /**
     * 在缓存中查找被撤回的消息
     */
    private suspend fun findRecalledMessage(sender: String, packageName: String): MessageEntity? {
        // 1. 先在内存缓存中找
        val cached = messageCache.lastOrNull {
            it.packageName == packageName &&
            (it.sender == sender || it.sender.contains(sender) || sender.contains(it.sender)) &&
            !it.isRecalled
        }
        if (cached != null) return cached

        // 2. 再去数据库找
        val cutoffTime = System.currentTimeMillis() - 60_000 // 最近1分钟内
        return dao.findLatestBySender(packageName, sender, cutoffTime)
    }

    /**
     * 捕获新消息并存入数据库
     */
    private fun captureMessage(
        text: String,
        packageName: String,
        appName: String,
        textNodes: List<TextNodeInfo>
    ) {
        val contentHash = md5(text)
        val now = System.currentTimeMillis()

        // 查找可能的发送者
        val sender = textNodes.find { it.text == text }?.sender ?: ""

        val entity = MessageEntity(
            packageName = packageName,
            appName = appName,
            sender = sender,
            content = text,
            timestamp = now,
            contentHash = contentHash,
            captureMethod = "accessibility"
        )

        // 加入缓存
        messageCache.addFirst(entity)
        if (messageCache.size > AppConstants.MAX_CACHE_SIZE) {
            messageCache.removeLast()
        }

        // 存入数据库（异步，防重复）
        serviceScope.launch {
            try {
                val existing = dao.findByContentHash(contentHash, now - AppConstants.DEDUP_WINDOW_MS)
                if (existing == null) {
                    dao.insert(entity)
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存消息出错", e)
            }
        }
    }

    // ===== 通知 =====

    private fun startForegroundNotification() {
        val channel = NotificationChannel(
            AppConstants.CHANNEL_ID,
            AppConstants.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "防撤回服务运行中"
            setShowBadge(false)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, AppConstants.CHANNEL_ID)
            .setContentTitle("消息保镖运行中")
            .setContentText("正在守护微信/QQ/钉钉消息")
            .setSmallIcon(R.drawable.ic_shield_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        startForeground(AppConstants.NOTIFICATION_ID_FOREGROUND, notification)
    }

    private fun showRecallNotification(message: MessageEntity) {
        val nm = getSystemService(NotificationManager::class.java)

        val channelId = "recall_alert"
        val channel = NotificationChannel(
            channelId, "撤回提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "有人撤回了消息"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, message.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sender = if (message.sender.isNotBlank()) message.sender else "未知"
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("⚡ ${message.appName}消息被撤回")
            .setContentText("$sender: ${message.content.take(50)}")
            .setSmallIcon(R.drawable.ic_shield_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText("$sender: ${message.content.take(200)}")
            )
            .build()

        nm.notify(AppConstants.NOTIFICATION_ID_RECALL + message.id.toInt(), notification)
    }

    // ===== 工具方法 =====

    private fun trimCache() {
        while (messageCache.size > AppConstants.MAX_CACHE_SIZE) {
            messageCache.removeLast()
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ===== 数据类 =====

    private data class TextNodeInfo(
        val text: String,
        val sender: String,
        val className: String
    )

    private data class RecallMatch(
        val recallText: String,
        val sender: String
    )

    companion object {
        /**
         * 检查无障碍服务是否正在运行
         */
        fun isRunning(context: android.content.Context): Boolean {
            return context.getSharedPreferences(AppConstants.PREF_NAME, android.content.Context.MODE_PRIVATE)
                .getBoolean(AppConstants.KEY_SERVICE_ENABLED, false)
        }
    }
}
