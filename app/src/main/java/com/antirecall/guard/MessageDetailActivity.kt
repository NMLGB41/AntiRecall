package com.antirecall.guard

import android.os.Bundle
import android.text.format.DateFormat
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.antirecall.guard.data.MessageDatabase
import com.antirecall.guard.util.AppConstants
import kotlinx.coroutines.launch
import java.util.Date

class MessageDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val messageId = intent.getLongExtra("message_id", -1)
        if (messageId == -1L) {
            finish()
            return
        }

        val dao = MessageDatabase.getInstance(this).messageDao()

        // 简单的详情布局
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        lifecycleScope.launch {
            val messages = dao.getMessagesPaged(Int.MAX_VALUE, 0)
            val message = messages.find { it.id == messageId }

            if (message == null) {
                finish()
                return@launch
            }

            val appName = AppConstants.APP_NAME_MAP[message.packageName] ?: message.packageName

            // 标题
            layout.addView(TextView(this@MessageDetailActivity).apply {
                text = if (message.isRecalled) "⚡ 已撤回的消息" else "消息详情"
                textSize = 22f
                setTextColor(if (message.isRecalled) 0xFFFF4444.toInt() else 0xFF333333.toInt())
            })

            layout.addView(TextView(this@MessageDetailActivity).apply {
                text = "\n来源: $appName"
                textSize = 16f
            })

            if (message.sender.isNotBlank()) {
                layout.addView(TextView(this@MessageDetailActivity).apply {
                    text = "发送者: ${message.sender}"
                    textSize = 16f
                })
            }

            if (message.chatName != null) {
                layout.addView(TextView(this@MessageDetailActivity).apply {
                    text = "聊天: ${message.chatName}"
                    textSize = 16f
                })
            }

            val timeStr = DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(message.timestamp))
            layout.addView(TextView(this@MessageDetailActivity).apply {
                text = "时间: $timeStr"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
            })

            if (message.isRecalled && message.recallTime != null) {
                val recallTimeStr = DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(message.recallTime))
                layout.addView(TextView(this@MessageDetailActivity).apply {
                    text = "撤回时间: $recallTimeStr"
                    textSize = 14f
                    setTextColor(0xFFFF4444.toInt())
                })
            }

            layout.addView(TextView(this@MessageDetailActivity).apply {
                text = "\n消息内容："
                textSize = 16f
                setTextColor(0xFF333333.toInt())
            })

            layout.addView(TextView(this@MessageDetailActivity).apply {
                text = message.content
                textSize = 18f
                setPadding(24, 24, 24, 24)
                setTextColor(0xFF111111.toInt())
            })
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(layout)
        }

        setContentView(scrollView)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "消息详情"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
