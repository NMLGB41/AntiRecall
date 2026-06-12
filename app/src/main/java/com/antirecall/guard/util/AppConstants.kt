package com.antirecall.guard.util

/**
 * 应用常量
 */
object AppConstants {

    // ===== 监控的应用包名 =====
    const val WECHAT_PKG = "com.tencent.mm"
    const val QQ_PKG = "com.tencent.mobileqq"
    const val DINGTALK_PKG = "com.alibaba.android.rimet"
    // 钉钉新包名
    const val DINGTALK_PKG_NEW = "com.larksuite.lark"

    val MONITORED_PACKAGES = setOf(WECHAT_PKG, QQ_PKG, DINGTALK_PKG, DINGTALK_PKG_NEW)

    // ===== 应用显示名映射 =====
    val APP_NAME_MAP = mapOf(
        WECHAT_PKG to "微信",
        QQ_PKG to "QQ",
        DINGTALK_PKG to "钉钉",
        DINGTALK_PKG_NEW to "钉钉"
    )

    // ===== 撤回检测正则 =====
    // 微信: "XXX撤回了一条消息"
    // QQ: "XXX撤回了一条消息"
    // 钉钉: "XXX撤回了一条消息" 或 "XXX recalled a message"
    val RECALL_PATTERNS = listOf(
        Regex("""(.{1,20}?)撤回了一条消息"""),
        Regex("""(.{1,20}?) recalled a message"""),
        Regex("""(.{1,20}?)撤回了一条图文"""),
        Regex("""(.{1,20}?) recalled an image"""),
        Regex(""""(.{1,20}?)" 撤回了一条消息"""),
        Regex(""""(.{1,20}?)" recalled a message"""),
    )

    // ===== 通知渠道 =====
    const val CHANNEL_ID = "anti_recall_channel"
    const val CHANNEL_NAME = "防撤回提醒"
    const val NOTIFICATION_ID_FOREGROUND = 1001
    const val NOTIFICATION_ID_RECALL = 2001

    // ===== 数据库 =====
    const val DB_NAME = "anti_recall_db"

    // ===== 偏好设置 =====
    const val PREF_NAME = "anti_recall_prefs"
    const val KEY_SERVICE_ENABLED = "service_enabled"
    const val KEY_WECHAT_ENABLED = "wechat_enabled"
    const val KEY_QQ_ENABLED = "qq_enabled"
    const val KEY_DINGTALK_ENABLED = "dingtalk_enabled"
    const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
    const val KEY_AUTO_START = "auto_start"

    // ===== 消息去重时间窗口（毫秒）=====
    const val DEDUP_WINDOW_MS = 3000L

    // ===== 最大缓存消息数（防内存泄漏）=====
    const val MAX_CACHE_SIZE = 500
}
