package com.wristcontrol.app.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
 * **崩溃防护是本服务的首要设计目标**：
 * 任何一次未捕获异常都会让服务退出，用户看到的就是"点启动桥接就闪退"。
 * 因此 onCreate / onStartCommand / 心跳 / 指令处理全部包 try-catch，
 * SDK 初始化失败也只降级为"桥接不可用"，不影响服务存活。
 *
 * 其他要点：
 * - 只在执行动作的瞬间持有唤醒锁，空闲不持有，避免耗电。
 * - 无障碍服务不可用时，指令被丢弃并记日志，绝不静默假装成功。
 * - 每 5 秒推一次状态；每条指令执行后回 ack。
 */
class ControlService : Service() {

    private companion object {
        const val TAG = "WristControl"
        const val CHANNEL_ID = "wristcontrol_bridge"
        const val NOTIFICATION_ID = 1001
        const val HEARTBEAT_MS = 5_000L
        const val WAKE_TIMEOUT_MS = 3_000L
    }

    private var transport: XiaomiWearableTransport? = null
    private var power: PowerManager? = null
    private var audio: AudioManager? = null

    @Volatile private var running = false
    private var lastHeartbeat = 0L
    private val seq = AtomicInteger(0)

    /** 桥接层的当前状态描述，显示在通知和主页上。 */
    @Volatile private var bridgeStatus = "正在初始化"

    private val handler = Handler(Looper.getMainLooper())
    private val heartbeatTask = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                pushStatus()
            } catch (t: Throwable) {
                Log.w(TAG, "心跳异常: ${t.javaClass.simpleName}")
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ServiceRef.attach(this)
        // 这一段整体兜底：哪怕取系统服务失败，也不能让进程死掉
        try {
            power = getSystemService(Context.POWER_SERVICE) as? PowerManager
            audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (t: Throwable) {
            Log.w(TAG, "获取系统服务失败: ${t.javaClass.simpleName}")
        }
        try {
            createChannel()
        } catch (t: Throwable) {
            Log.w(TAG, "创建通知渠道失败: ${t.javaClass.simpleName}")
        }
        // 注意：这里**不**初始化 SDK。SDK 类加载失败会直接崩掉 onCreate，
        // 挪到 onStartCommand 里用 runCatching 包住，失败只降级不影响服务。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            // Android 12+ 前台服务启动受限时会抛异常，此处不能让它崩掉进程
            Log.w(TAG, "startForeground 失败: ${t.javaClass.simpleName} ${t.message}")
            bridgeStatus = "前台服务被系统拒绝"
        }

        if (!running) {
            running = true
            startBridge()
            handler.post(heartbeatTask)
        }
        return START_STICKY
    }

    /** 启动 SDK 桥接。失败只更新状态，不抛异常。 */
    private fun startBridge() {
        try {
            // 用全局单例：与 Activity 探测共用同一套 SDK 连接，
            // 避免 Service 每次新建实例都要重新绑定宿主服务
            val t = XiaomiWearableTransport.get(this)
            transport = t
            t.start(
                onCommand = ::handleCommandSafely,
                onStatus = { pushStatus() },
                onError = { reason ->
                    bridgeStatus = reason
                    updateNotification()
                },
            )
            bridgeStatus = "已连接"
        } catch (t: Throwable) {
            // NoClassDefFoundError / UnsatisfiedLinkError 都会走到这里
            Log.w(TAG, "桥接启动失败: ${t.javaClass.simpleName} ${t.message}")
            bridgeStatus = "SDK 不可用：${t.javaClass.simpleName}"
            transport = null
        }
        updateNotification()
    }

    private fun handleCommandSafely(command: Command) {
        try {
            handleCommand(command)
        } catch (t: Throwable) {
            Log.w(TAG, "指令处理异常: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun handleCommand(command: Command) {
        if (command is Command.Ack) {
            DiagnoseLog.append(this, "指令: ack(${command.refId}) 不执行动作")
            return
        }
        if (command is Command.Ping) {
            DiagnoseLog.append(this, "指令: ping 不执行动作")
            return
        }
        if (command is Command.SyncRequest) {
            syncGestures()
            return
        }
        if (command is Command.GestureHit) {
            handleGesture(command.path)
            return
        }
        if (command is Command.TiltLive) {
            lastLiveDir = command.dir
            lastLivePath = command.path
            lastTilt = command
            // 加速度数值变化很快，日志里只记方向，避免刷屏
            DiagnoseLog.append(this, "实时方向: dir=${command.dir} path=${command.path}")
            liveListener?.invoke(command.dir, command.path)
            return
        }
        if (command is Command.TiltState) {
            tiltOn = command.on
            DiagnoseLog.append(this, "体感开关: ${if (command.on) "开" else "关"}")
            pushStatus()
            return
        }
        DiagnoseLog.append(this, "执行指令: ${command.javaClass.simpleName}")
        DiagnoseLog.append(this, "  无障碍服务就绪=${ControlAccessibilityService.Access.isReady()}")

        val lock = try {
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristControl:cmd")
        } catch (t: Throwable) {
            null
        }
        try {
            lock?.acquire(WAKE_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "获取唤醒锁失败: ${t.javaClass.simpleName}")
        }

        var executed = false
        try {
            executed = when (command) {
                is Command.Move -> withGestures { it.swipe(command.dx, command.dy) }
                Command.Tap -> withGestures { it.tap() }
                Command.Back -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                Command.Home -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                Command.Recents -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                is Command.Volume -> adjustVolume(command.delta)
                Command.ScreenOff -> screenOff()
                Command.Ping -> false
                is Command.Ack -> false
                is Command.GestureHit -> false
                Command.SyncRequest -> false
                is Command.TiltLive -> false
                is Command.TiltState -> false
            }
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (t: Throwable) {
                Log.w(TAG, "释放唤醒锁失败: ${t.javaClass.simpleName}")
            }
        }

        DiagnoseLog.append(this, "  执行结果=$executed")
        // 只有真的执行成功才回执，失败不回执，手环会停在"已发送…"
        if (executed) ack()
    }

    /**
     * 最近一次实时倾斜数据，供界面读取。
     *
     * 手环**只有加速度计，没有陀螺仪**，所以这里只有 ax/ay/az 三轴加速度。
     * 界面上要如实标注，不能让用户以为有陀螺仪数据。
     */
    @Volatile
    var lastTilt: com.wristcontrol.app.protocol.Command.TiltLive? = null
        private set

    /** 体感模式是否开启（由手环同步过来）。 */
    @Volatile
    private var tiltOn = false

    private val gestureStore by lazy { com.wristcontrol.app.gesture.GestureStore(this) }
    private val configStore by lazy { com.wristcontrol.app.gesture.GestureConfigStore(this) }

    /** 最近一次实时方向，供界面读取。 */
    @Volatile
    var lastLiveDir: String = ""
        private set
    @Volatile
    var lastLivePath: String = ""
        private set

    /** 实时方向的监听器；界面注册后能拿到每一帧。 */
    @Volatile
    var liveListener: ((String, String) -> Unit)? = null

    /** 把当前配置下发给手环。 */
    fun pushConfig() {
        val c = configStore.load()
        DiagnoseLog.append(this, "下发配置: 死区=${c.deadZone}° 保持=${c.holdMs}ms 静止=${c.idleEndMs}ms")
        transport?.sendRaw(
            com.wristcontrol.app.protocol.StatusCodec.encodeConfigSync(
                deadZone = c.deadZone,
                holdMs = c.holdMs,
                idleEndMs = c.idleEndMs,
                live = c.showLive,
                rotationOffset = c.rotationOffset,
            ),
        )
    }

    /**
     * 手环识别出手势后，查映射并执行。
     *
     * 识别在手环、映射在手机：改映射不用重装 RPK。
     * 匹配用编辑距离，没有足够接近的模板就不动——宁可没反应，也不猜错。
     */
    private fun handleGesture(pathText: String) {
        val actual = com.wristcontrol.app.gesture.GestureCodec.decodePath(pathText)
        DiagnoseLog.append(this, "手势: $pathText -> ${actual.joinToString(",")}")

        val templates = gestureStore.load()
        if (templates.isEmpty()) {
            DiagnoseLog.append(this, "  -> 没有已学习的手势")
            return
        }

        val hit = com.wristcontrol.app.gesture.GestureMatcher.bestMatch(actual, templates)
        if (hit == null) {
            DiagnoseLog.append(this, "  -> 没有匹配的模板（差太多，不执行）")
            return
        }

        DiagnoseLog.append(this, "  -> 匹配到「${hit.name}」执行 ${hit.action}")
        val cmd = when (hit.action) {
            "back" -> Command.Back
            "home" -> Command.Home
            "recents" -> Command.Recents
            "tap" -> Command.Tap
            "screenoff" -> Command.ScreenOff
            "volup" -> Command.Volume(1)
            "voldown" -> Command.Volume(-1)
            "up" -> Command.Move(0f, -1f)
            "down" -> Command.Move(0f, 1f)
            "left" -> Command.Move(-1f, 0f)
            "right" -> Command.Move(1f, 0f)
            else -> null
        }
        if (cmd == null) {
            DiagnoseLog.append(this, "  -> 未知动作 ${hit.action}")
            return
        }
        handleCommand(cmd)
    }

    /** 把手势模板推给手环。 */
    private fun syncGestures() {
        val list = gestureStore.load()
        DiagnoseLog.append(this, "同步手势模板: ${list.size} 条")
        transport?.sendRaw(
            com.wristcontrol.app.protocol.StatusCodec.encodeGestureSync(list),
        )
        pushConfig()
    }

    private inline fun withGestures(block: (com.wristcontrol.app.input.GestureExecutor) -> Boolean): Boolean {
        val service = ControlAccessibilityService.Access.current()
        if (service == null) {
            Log.w(TAG, "无障碍服务未启用，指令被丢弃")
            return false
        }
        return try {
            block(service.gestures)
        } catch (t: Throwable) {
            Log.w(TAG, "手势执行异常: ${t.javaClass.simpleName}")
            false
        }
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
            Log.w(TAG, "performGlobalAction 抛出: ${t.message}")
            false
        }
    }

    private fun adjustVolume(delta: Int): Boolean {
        if (delta == 0) return false
        val mgr = audio ?: return false
        val direction = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        return try {
            mgr.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "adjustStreamVolume 抛出: ${t.message}")
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
        try {
            transport?.sendRaw(StatusCodec.encodeAck("cmd"))
        } catch (t: Throwable) {
            Log.w(TAG, "发送 ack 失败: ${t.javaClass.simpleName}")
        }
    }

    private fun pushStatus() {
        val now = System.currentTimeMillis()
        if (now - lastHeartbeat < HEARTBEAT_MS - 500) return
        lastHeartbeat = now

        val battery = try {
            (getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (t: Throwable) {
            -1
        }
        val t = transport ?: return
        try {
            t.sendRaw(
                StatusCodec.encode(
                    id = "st-${seq.incrementAndGet()}",
                    link = if (t.hasNode()) "connected" else "disconnected",
                    battery = battery,
                    listening = ControlAccessibilityService.Access.isReady(),
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "推送状态失败: ${t.javaClass.simpleName}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
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
            .setContentText(notificationText())
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun notificationText(): String {
        val a11y = if (ControlAccessibilityService.Access.isReady()) "手势就绪" else "手势未开"
        return "$a11y · $bridgeStatus"
    }

    private fun updateNotification() {
        try {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            mgr.notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "更新通知失败: ${t.javaClass.simpleName}")
        }
    }

    override fun onDestroy() {
        ServiceRef.detach(this)
        running = false
        try {
            handler.removeCallbacks(heartbeatTask)
        } catch (t: Throwable) {
            // 忽略
        }
        try {
            transport?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "停止桥接失败: ${t.javaClass.simpleName}")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}