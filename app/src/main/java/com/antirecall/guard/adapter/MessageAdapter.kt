package com.antirecall.guard.adapter

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.antirecall.guard.R
import com.antirecall.guard.data.MessageEntity
import com.antirecall.guard.util.AppConstants
import java.util.Date

class MessageAdapter(
    private val onClick: (MessageEntity) -> Unit
) : ListAdapter<MessageEntity, MessageAdapter.ViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return ViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onClick: (MessageEntity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvAppName: TextView = itemView.findViewById(R.id.tv_app_name)
        private val tvSender: TextView = itemView.findViewById(R.id.tv_sender)
        private val tvContent: TextView = itemView.findViewById(R.id.tv_content)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val tvRecalled: TextView = itemView.findViewById(R.id.tv_recalled)
        private val recalledBadge: View = itemView.findViewById(R.id.recalled_badge)

        fun bind(message: MessageEntity) {
            val appName = AppConstants.APP_NAME_MAP[message.packageName] ?: message.packageName

            tvAppName.text = when (message.packageName) {
                AppConstants.WECHAT_PKG -> "💬 $appName"
                AppConstants.QQ_PKG -> "🐧 $appName"
                AppConstants.DINGTALK_PKG, AppConstants.DINGTALK_PKG_NEW -> "📌 $appName"
                else -> appName
            }

            tvSender.text = if (message.sender.isNotBlank()) message.sender else "未知"

            tvContent.text = message.content.take(100) +
                    if (message.content.length > 100) "..." else ""

            val timeStr = DateFormat.format("HH:mm", Date(message.timestamp))
            tvTime.text = timeStr.toString()

            if (message.isRecalled) {
                recalledBadge.visibility = View.VISIBLE
                tvRecalled.visibility = View.VISIBLE
                tvRecalled.text = if (message.recallTime != null) {
                    val recallStr = DateFormat.format("HH:mm", Date(message.recallTime))
                    "已撤回 ($recallStr)"
                } else {
                    "已撤回"
                }
                itemView.setBackgroundColor(0x1AFF4444.toInt()) // 淡红色背景
            } else {
                recalledBadge.visibility = View.GONE
                tvRecalled.visibility = View.GONE
                itemView.setBackgroundColor(0x00000000) // 透明
            }

            itemView.setOnClickListener { onClick(message) }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<MessageEntity>() {
        override fun areItemsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean {
            return oldItem == newItem
        }
    }
}
