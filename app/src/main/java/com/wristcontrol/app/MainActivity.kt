package com.wristcontrol.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 主页：检查无障碍、启动桥接、显示真实状态。
 *
 * 刻意不做花哨 UI：这个应用的价值在手环那一侧，手机端是配置页。
 * 所有系统调用都包了 try-catch——这类页面最容易因为厂商 ROM 差异崩掉。
 */
class MainActivity : Activity() {

    private companion object {
        const val TAG = "WristControlUI"
        const val REQ_NOTIFY = 101
    }

    private lateinit var statusText: TextView
    private var liveLog: TextView? = null

    /** 是否正停留在手势面板。返回键据此决定回主界面还是退出。 */
    private var inGesturePanel = false

    /**
     * 返回主界面。
     *
     * 之前按返回直接 finish() 会退到桌面，用户要的是回 App 起始页。
     */
    fun showMainPage() {
        gesturePanel = null
        inGesturePanel = false
        try {
            buildUi()
        } catch (t: Throwable) {
            Log.w(TAG, "重建主界面失败: ${t.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (inGesturePanel) {
            showMainPage()
        } else {
            super.onBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            buildUi()
            requestNotificationPermissionIfNeeded()
        } catch (t: Throwable) {
            Log.w(TAG, "界面构建失败: ${t.javaClass.simpleName}")
            // 连界面都建不起来时，至少给用户一个提示，不要白屏
            val fallback = TextView(this).apply {
                text = "界面初始化失败：${t.javaClass.simpleName}\n${t.message ?: ""}"
                textSize = 16f
                setPadding(48, 96, 48, 48)
            }
            setContentView(fallback)
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "WristControl"
            textSize = 26f
        })

        root.addView(TextView(this).apply {
            text = "用手环遥控这台手机。\n\n" +
                "第一步：开启「手势服务」无障碍权限。\n" +
                "第二步：启动桥接，让手环连上。\n\n" +
                "如果启动后手环没反应，多半是没装「小米运动健康」——" +
                "桥接依赖它的官方互联服务。"
            textSize = 15f
            setPadding(0, 32, 0, 32)
        })

        root.addView(Button(this).apply {
            text = "开启无障碍服务"
            setOnClickListener { openAccessibilitySettings() }
        })

        root.addView(Button(this).apply {
            text = "启动桥接"
            setOnClickListener { startBridge() }
        })

        root.addView(Button(this).apply {
            text = "查看日志（排查用）"
            setOnClickListener { showLogHint() }
        })

        root.addView(Button(this).apply {
            text = "复制日志到剪贴板"
            setOnClickListener {
                val file = java.io.File(com.wristcontrol.app.transport.DiagnoseLog.path(this@MainActivity))
                if (!file.exists()) {
                    statusText.text = "还没有日志。先点「启动桥接」试一次。"
                } else {
                    try {
                        val text = file.readText()
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("WristControl 诊断日志", text))
                        statusText.text = "已复制 ${text.length} 个字符到剪贴板。\n直接粘贴给开发者即可。"
                    } catch (t: Throwable) {
                        statusText.text = "复制失败：${t.javaClass.simpleName}"
                    }
                }
            }
        })

        root.addView(Button(this).apply {
            text = "前台探测一次（在 Activity 里查节点）"
            setOnClickListener {
                // 官方示例是在 Activity 的 onCreate 里查节点就能拿到。
                // 这里手动触发一次，用来对比「Activity 上下文」与「Service 上下文」是否有差异。
                try {
                    // 与 Service 共用单例，避免各建一套连接
                    val transport = com.wristcontrol.app.transport.XiaomiWearableTransport.get(this@MainActivity)
                    val api = com.xiaomi.xms.wearable.Wearable.getNodeApi(applicationContext)
                    api.connectedNodes
                        .addOnSuccessListener { nodes ->
                            val n = nodes?.size ?: 0
                            val first = nodes?.firstOrNull()
                            val msg = "Activity 探测：节点数=$n 名称=${first?.name ?: "(无)"} id=${first?.id ?: "(空)"}"
                            com.wristcontrol.app.transport.DiagnoseLog.append(this@MainActivity, msg)
                            statusText.text = msg
                        }
                        .addOnFailureListener { e ->
                            val msg = "Activity 探测失败：${e.javaClass.name} ${e.message}"
                            com.wristcontrol.app.transport.DiagnoseLog.append(this@MainActivity, msg)
                            statusText.text = msg
                        }
                } catch (t: Throwable) {
                    statusText.text = "Activity 探测抛出：${t.javaClass.simpleName}"
                }
            }
        })

        root.addView(Button(this).apply {
            text = "手势控制（体感）"
            setOnClickListener {
                try {
                    val panel = com.wristcontrol.app.gesture.GesturePanel(this@MainActivity)
                    gesturePanel = panel
                    inGesturePanel = true
                    setContentView(panel.build())
                    panel.onResume()
                } catch (t: Throwable) {
                    statusText.text = "打开手势面板失败：${t.javaClass.simpleName}"
                }
            }
        })

        root.addView(Button(this).apply {
            text = "查看诊断日志"
            setOnClickListener {
                val path = com.wristcontrol.app.transport.DiagnoseLog.path(this@MainActivity)
                val file = java.io.File(path)
                if (!file.exists()) {
                    statusText.text = "还没有日志。先点「启动桥接」试一次。"
                } else {
                    try {
                        val text = file.readText()
                        // 只显示最后 60 行，避免撑爆界面
                        val tail = text.lines().takeLast(60).joinToString("\n")
                        statusText.text = "日志路径：\n$path\n\n—— 最后 60 行 ——\n$tail"
                    } catch (t: Throwable) {
                        statusText.text = "读日志失败：${t.javaClass.simpleName}"
                    }
                }
            }
        })

        // 实时消息日志
        root.addView(TextView(this).apply {
            text = "实时消息日志"
            textSize = 16f
            setPadding(0, 32, 0, 8)
        })
        liveLog = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, 0, 0, 16)
            text = "（等待消息…）"
        }
        root.addView(liveLog)

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 40, 0, 0)
        }
        root.addView(statusText)

        setContentView(scroll)
    }

    private var gesturePanel: com.wristcontrol.app.gesture.GesturePanel? = null

    override fun onResume() {
        super.onResume()
        // 注册日志监听，实时刷新界面
        try {
            com.wristcontrol.app.transport.DiagnoseLog.listener = { line ->
                runOnUiThread {
                    val box = liveLog ?: return@runOnUiThread
                    val lines = com.wristcontrol.app.transport.DiagnoseLog.recentLines(25)
                    box.text = lines.joinToString("\n")
                }
            }
            // 立即显示已有的
            liveLog?.text = com.wristcontrol.app.transport.DiagnoseLog.recentLines(25)
                .joinToString("\n").ifEmpty { "（还没有日志）" }
        } catch (t: Throwable) {
            // 日志显示失败不影响主流程
        }
        try {
            refreshStatus()
        } catch (t: Throwable) {
            Log.w(TAG, "刷新状态失败: ${t.javaClass.simpleName}")
        }
        try {
            gesturePanel?.onResume()
        } catch (t: Throwable) {
            Log.w(TAG, "手势面板恢复失败: ${t.javaClass.simpleName}")
        }
    }

    override fun onPause() {
        super.onPause()
        com.wristcontrol.app.transport.DiagnoseLog.listener = null
        try {
            gesturePanel?.onPause()
        } catch (t: Throwable) {
            Log.w(TAG, "手势面板暂停失败: ${t.javaClass.simpleName}")
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (t: Throwable) {
            // 少数 ROM 的这个 action 不可用，退回应用详情页
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    ),
                )
            } catch (t2: Throwable) {
                statusText.text = "无法打开设置：${t2.javaClass.simpleName}"
            }
        }
    }

    private fun startBridge() {
        val intent = Intent(this, com.wristcontrol.app.transport.ControlService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            statusText.text = buildString {
                append("手势服务：")
                append(if (isAccessibilityEnabled()) "已开启 ✓" else "未开启 ✗")
                append("\n\n桥接已启动，状态见通知栏。")
                append("\n如果通知栏没有出现「WristControl 运行中」，说明系统拒绝了前台服务。")
            }
        } catch (t: Throwable) {
            statusText.text = "启动失败：${t.javaClass.simpleName}\n${t.message ?: ""}"
        }
    }

    private fun showLogHint() {
        statusText.text = "日志查看方式：\n\n" +
            "1. 电脑执行 adb logcat -s WristControl WristTransport WristA11y\n" +
            "2. 或安装「日志查看器」类应用\n\n" +
            "关键 tag：WristControl（服务）/ WristTransport（SDK）/ WristA11y（无障碍）"
    }

    private fun refreshStatus() {
        val enabled = isAccessibilityEnabled()
        statusText.text = buildString {
            append("手势服务：")
            append(if (enabled) "已开启 ✓" else "未开启 ✗")
            if (!enabled) {
                append("\n\n⚠️ 没有无障碍权限时，手环发来的点击和滑动都会被丢弃。")
            }
            append("\n\n若桥接启动后立即停止，通常是没装「小米运动健康」。")
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "请求通知权限失败: ${t.javaClass.simpleName}")
        }
    }

    /** 读系统设置判断无障碍服务是否真的被启用（比看静态实例更可靠）。 */
    private fun isAccessibilityEnabled(): Boolean = try {
        val expected = packageName + "/" + com.wristcontrol.app.input.ControlAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (enabled.isNullOrEmpty()) {
            false
        } else {
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            var found = false
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) {
                    found = true
                    break
                }
            }
            found
        }
    } catch (t: Throwable) {
        Log.w(TAG, "读取无障碍设置失败: ${t.javaClass.simpleName}")
        false
    }
}