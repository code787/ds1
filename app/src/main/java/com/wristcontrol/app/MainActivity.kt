package com.wristcontrol.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 主页：只做三件事——检查无障碍是否开启、启动桥接服务、显示状态。
 *
 * 刻意不做花哨 UI：这个应用的价值在手环那一侧，手机端是配置页。
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_NOTIFICATIONS = 100
    }

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                "第一步：开启下面的「手势服务」无障碍权限。\n" +
                "第二步：启动桥接，让手环连上。"
            textSize = 15f
            setPadding(0, 32, 0, 32)
        })

        root.addView(Button(this).apply {
            text = "开启无障碍服务"
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (t: Throwable) {
                    // 少数 ROM 没有这个入口，退回到应用详情页
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        })

        root.addView(Button(this).apply {
            text = "启动桥接"
            setOnClickListener { startBridge() }
        })

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 40, 0, 0)
        }
        root.addView(statusText)

        setContentView(scroll)
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** Android 13+ 前台服务的常驻通知需要运行时权限。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    private fun startBridge() {
        val intent = Intent(this, com.wristcontrol.app.transport.ControlService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (t: Throwable) {
            statusText.text = "启动失败：${t.javaClass.simpleName}\n${t.message ?: ""}"
            return
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = isAccessibilityEnabled()
        statusText.text = buildString {
            append("手势服务：")
            append(if (enabled) "已开启 ✓" else "未开启 ✗")
            append("\n桥接服务：见通知栏常驻提示")
            if (!enabled) {
                append("\n\n⚠️ 没有无障碍权限时，手环发来的点击和滑动都会被丢弃。")
            }
        }
    }

    /** 读系统设置判断无障碍服务是否真的被启用（比看静态实例更可靠）。 */
    private fun isAccessibilityEnabled(): Boolean {
        val expected = packageName + "/" +
            com.wristcontrol.app.input.ControlAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
