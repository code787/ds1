package com.wristcontrol.app.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.wristcontrol.app.MainActivity
import com.wristcontrol.app.input.ControlAccessibilityService
import com.wristcontrol.app.protocol.Command
import com.wristcontrol.app.protocol.StatusCodec
import java.util.concurrent.atomic.AtomicInteger

/**
 * 常驻前台服务：持有与手环的 SDK 通道，接收指令并执行。
 *
 * 设计要点：
 * - 只在执行动作的瞬间持有唤醒锁，空闲不持有，避免耗电。
 * - 无障碍服务不可用时，指令被丢弃并记日志，绝不静默假装成功。
 * - 每 5 秒推一次状态；每条指令执行后回 ack，手环据此消掉"已发送…"。
 */
class ControlService : Service() {

    private companion object {
        const val TAG = "WristControl"
        const val CHANNEL_ID = "wristcontrol_bridge"
        const val NOTIFICATION_ID = 1001
        const val HEARTBEAT_MS = 5_000L
        const val WAKE_TIMEOUT_MS = 3_000L
    }

    private lateinit var transport: XiaomiWearableTransport
    private lateinit var power: PowerManager
    private lateinit var audio: AudioManager

    @Volatile private var running = false
    private var lastHeartbeat = 0L
    private val seq = AtomicInteger(0)

    private val handler = Handler(Looper.getMainLooper())
    private val heartbeatTask = object : Runnable {
        override fun run() {
            if (!running) return
            pushStatus()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        transport = XiaomiWearableTransport(this)
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!running) {
            running = true
            transport.start(onCommand = ::handleCommand, onStatus = { pushStatus() })
            handler.post(heartbeatTask)
        }
        return START_STICKY
    }

    private fun handleCommand(command: Command) {
        // Ack / Ping 不执行任何动作，直接处理掉，不占用唤醒锁
        if (command is Command.Ack) {
            Log.d(TAG, "手环确认收到: ${command.refId}")
            return
        }
        if (command is Command.Ping) return

        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristControl:cmd")
        lock.acquire(WAKE_TIMEOUT_MS)
        var executed = false
        try {
            // Ack / Ping 已提前 return；这里仍保留分支，因为 Kotlin 无法从
            // 提前 return 推断 when 的穷尽性，缺分支会编译失败。
            executed = when (command) {
                is Command.Move -> withGestures { it.swipe(command.dx, command.dy) }
                Command.Tap -> withGestures { it.tap() }
                Command.Back -> global(AccessibilityService.GLOBAL_ACTION_BACK)
                Command.Home -> global(AccessibilityService.GLOBAL_ACTION_HOME)
                Command.Recents -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                is Command.Volume -> adjustVolume(command.delta)
                Command.ScreenOff -> screenOff()
                Command.Ping -> false
                is Command.Ack -> false
            }
        } finally {
            if (lock.isHeld) lock.release()
        }

        // 只有真的执行成功才回执，失败不回执，手环会停在"已发送…"
        if (executed) ack()
    }

    /**
     * 取无障碍服务并执行手势。
     *
     * 这里刻意不是 inline：inline 函数的 lambda 参数若被当作值传递，
     * 在 when 表达式里推断返回类型会出问题；普通函数更稳，开销可以忽略。
     */
    private fun withGestures(block: (com.wristcontrol.app.input.GestureExecutor) -> Boolean): Boolean {
        val service = ControlAccessibilityService.Access.current()
        if (service == null) {
            Log.w(TAG, "无障碍服务未启用，指令被丢弃")
            return false
        }
        return block(service.gestures)
    }

    private fun global(action: Int): Boolean {
        val service = ControlAccessibilityService.Access.current()
        if (service == null) {
            Log.w(TAG, "无障碍服务未启用，全局动作被丢弃")
            return false
        }
        return try {
            service.performGlobalAction(action)
        } catch (t: Throwable) {
            Log.w(TAG, "performGlobalAction threw: ${t.message}")
            false
        }
    }

    private fun adjustVolume(delta: Int): Boolean {
        if (delta == 0) return false
        val direction = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        return try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "adjustStreamVolume threw: ${t.message}")
            false
        }
    }

    private fun screenOff(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "锁屏需要 Android 9+")
            return false
        }
        return global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    private fun ack() {
        transport.sendRaw(StatusCodec.encodeAck("cmd"))
    }

    private fun pushStatus() {
        val now = System.currentTimeMillis()
        if (now - lastHeartbeat < HEARTBEAT_MS - 500) return
        lastHeartbeat = now

        val battery = try {
            (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (t: Throwable) {
            -1
        }
        val id = "st-${seq.incrementAndGet()}"
        transport.sendRaw(
            StatusCodec.encode(
                id = id,
                link = if (transport.hasNode()) "connected" else "disconnected",
                battery = battery,
                listening = ControlAccessibilityService.Access.isReady(),
            ),
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "手环连接", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("WristControl 运行中")
            .setContentText(
                if (ControlAccessibilityService.Access.isReady()) "手势服务已就绪" else "手势服务未启用",
            )
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(heartbeatTask)
        transport.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}