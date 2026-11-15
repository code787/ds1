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

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 40, 0, 0)
        }
        root.addView(statusText)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        try {
            refreshStatus()
        } catch (t: Throwable) {
            Log.w(TAG, "刷新状态失败: ${t.javaClass.simpleName}")
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
