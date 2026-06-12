package com.antirecall.guard.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 消息实体
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["packageName", "timestamp"]),
        Index(value = ["isRecalled"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 来源应用包名 */
    val packageName: String,

    /** 来源应用显示名 (微信/QQ/钉钉) */
    val appName: String,

    /** 发送者名称 */
    val sender: String,

    /** 消息内容 */
    val content: String,

    /** 捕获时间 */
    val timestamp: Long,

    /** 是否已被撤回 */
    val isRecalled: Boolean = false,

    /** 撤回时间 */
    val recallTime: Long? = null,

    /** 聊天对象/群名 */
    val chatName: String? = null,

    /** 捕获方式 (accessibility / notification) */
    val captureMethod: String = "accessibility",

    /** 消息内容的哈希值,用于去重 */
    val contentHash: String = ""
)

/**
 * DAO
 */
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesPaged(limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE packageName = :pkg ORDER BY timestamp DESC")
    fun getMessagesByPackage(pkg: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isRecalled = 1 ORDER BY timestamp DESC")
    fun getRecalledMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isRecalled = 1 ORDER BY timestamp DESC")
    suspend fun getRecalledMessagesList(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE packageName = :pkg AND sender = :sender AND isRecalled = 0 AND timestamp > :afterTime ORDER BY timestamp DESC LIMIT 1")
    suspend fun findLatestBySender(pkg: String, sender: String, afterTime: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE contentHash = :hash AND timestamp > :afterTime LIMIT 1")
    suspend fun findByContentHash(hash: String, afterTime: Long): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE isRecalled = 1")
    suspend fun getRecalledCount(): Int

    @Query("DELETE FROM messages WHERE timestamp < :beforeTime AND isRecalled = 0")
    suspend fun cleanOldMessages(beforeTime: Long): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
