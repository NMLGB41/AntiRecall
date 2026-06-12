package com.antirecall.guard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.antirecall.guard.data.MessageDao
import com.antirecall.guard.data.MessageDatabase
import com.antirecall.guard.data.MessageEntity
import com.antirecall.guard.databinding.ActivityMainBinding
import com.antirecall.guard.service.AntiRecallAccessibilityService
import com.antirecall.guard.util.AppConstants
import com.antirecall.guard.adapter.MessageAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dao: MessageDao
    private lateinit var adapter: MessageAdapter

    /** 当前过滤: null=全部, String=按包名/特殊值过滤, Set<String>=多包名过滤 */
    private var currentFilter: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "消息保镖"

        dao = MessageDatabase.getInstance(this).messageDao()

        setupRecyclerView()
        setupFilters()
        setupFab()
        checkServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        checkServiceStatus()
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter { message ->
            // 点击消息查看详情
            val intent = Intent(this, MessageDetailActivity::class.java)
            intent.putExtra("message_id", message.id)
            startActivity(intent)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // 加载消息
        loadMessages()

        // 下拉刷新
        binding.swipeRefresh.setOnRefreshListener {
            loadMessages()
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun loadMessages() {
        lifecycleScope.launch {
            try {
                val messages = when (val filter = currentFilter) {
                    null -> dao.getMessagesPaged(200, 0)
                    is String -> when (filter) {
                        "recalled" -> dao.getRecalledMessagesList()
                        AppConstants.WECHAT_PKG -> dao.getMessagesPaged(200, 0).filter { it.packageName == AppConstants.WECHAT_PKG }
                        AppConstants.QQ_PKG -> dao.getMessagesPaged(200, 0).filter { it.packageName == AppConstants.QQ_PKG }
                        else -> dao.getMessagesPaged(200, 0)
                    }
                    is Set<*> -> dao.getMessagesPaged(200, 0).filter { it.packageName in filter }
                    else -> dao.getMessagesPaged(200, 0)
                }

                adapter.submitList(messages)

                // 空状态
                if (messages.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                }

                // 更新撤回计数
                val recalledCount = dao.getRecalledCount()
                binding.tvRecallCount.text = "已拦截 $recalledCount 条撤回"

            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "加载消息失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupFilters() {
        binding.chipAll.setOnClickListener {
            currentFilter = null
            loadMessages()
        }

        binding.chipWechat.setOnClickListener {
            currentFilter = AppConstants.WECHAT_PKG
            loadMessages()
        }

        binding.chipQq.setOnClickListener {
            currentFilter = AppConstants.QQ_PKG
            loadMessages()
        }

        binding.chipDingtalk.setOnClickListener {
            currentFilter = setOf(AppConstants.DINGTALK_PKG, AppConstants.DINGTALK_PKG_NEW)
            loadMessages()
        }

        binding.chipRecalled.setOnClickListener {
            currentFilter = "recalled"
            loadMessages()
        }
    }

    private fun setupFab() {
        binding.fabSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun checkServiceStatus() {
        val isRunning = AntiRecallAccessibilityService.isRunning(this)

        if (isRunning) {
            binding.tvServiceStatus.text = "🟢 防撤回服务运行中"
            binding.tvServiceStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.btnEnableService.visibility = View.GONE
        } else {
            binding.tvServiceStatus.text = "🔴 防撤回服务未开启"
            binding.tvServiceStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            binding.btnEnableService.visibility = View.VISIBLE
        }

        binding.btnEnableService.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "请在设置中找到「消息保镖」并开启无障碍服务", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun showSettingsDialog() {
        val options = arrayOf(
            "开启通知监听权限",
            "清空所有消息记录",
            "关于消息保镖"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        startActivity(intent)
                    }
                    1 -> {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("确认清空？")
                            .setMessage("将删除所有已捕获的消息记录，此操作不可恢复！")
                            .setPositiveButton("清空") { _, _ ->
                                lifecycleScope.launch {
                                    dao.deleteAll()
                                    loadMessages()
                                    Toast.makeText(this@MainActivity, "已清空", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    2 -> {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("消息保镖 v1.0")
                            .setMessage(
                                "支持微信、QQ、钉钉的防撤回功能\n\n" +
                                "原理：通过无障碍服务监控聊天界面，" +
                                "捕获消息内容并检测撤回事件。\n\n" +
                                "⚠️ 本应用仅用于查看被撤回的消息，" +
                                "不会上传任何数据。"
                            )
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                }
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                loadMessages()
                true
            }
            R.id.action_open_accessibility -> {
                openAccessibilitySettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
