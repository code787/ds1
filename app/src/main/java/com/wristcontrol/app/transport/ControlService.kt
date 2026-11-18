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
                onStatus = {
                    // safeStatus 成功推帧才算真的连上
                    bridgeStatus = "已连接"
                    updateNotification()
                    pushStatus(force = true)
                },
                onProgress = { msg ->
                    bridgeStatus = msg
                    updateNotification()
                },
                onError = { reason ->
                    bridgeStatus = "桥接失败：$reason"
                    updateNotification()
                },
            )
            // start 是异步的，这里不能提前宣布「已连接」
            bridgeStatus = "正在连接手环…"
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
            pushStatus(force = true)   // 手环刚进来，立刻推一次
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
            tiltCount++
            // 加速度数值变化很快，日志里只记方向，避免刷屏
            DiagnoseLog.append(this, "实时方向: dir=${command.dir} path=${command.path}")
            liveListener?.invoke(command.dir, command.path)
            return
        }
        if (command is Command.RawAccel) {
            // 识别全在手机端：手环只发原始值
            tiltCount++

            // 收到 raw 就说明传感器确实在工作——
            // 不依赖手环那条可能丢失的 sensor 帧。
            if (!sensorOk) {
                sensorOk = true
                sensorMsg = ""
            }

            // 校准采样窗口：收集原始帧，窗口结束后取平均作为基线
            collectCalSample(command.x, command.y, command.z)

            // 界面要读的数据：卡尔曼滤波后的值、角度、方向
            val result = engine.feed(command.x, command.y, command.z)

            lastTilt = Command.TiltLive(
                dir = engine.lastDir,
                path = engine.currentPath().joinToString(","),
                ax = engine.lastX,
                ay = engine.lastY,
                az = command.z,
                angle = engine.lastTilt,   // 用相对水平面的倾角，不是方位角
            )
            lastLiveDir = engine.lastDir
            lastLivePath = engine.currentPath().joinToString(",")

            if (result != null && result.isNotEmpty()) {
                DiagnoseLog.append(this, "识别出手势: ${result.joinToString(",")}")
                handleGesture(result.joinToString(","))
            }
            return
        }
        if (command is Command.SensorState) {
            sensorOk = command.ok
            sensorMsg = command.msg
            DiagnoseLog.append(this, "传感器订阅: ${if (command.ok) "成功" else "失败"} ${command.msg}")
            return
        }
        if (command is Command.TiltState) {
            tiltOn = command.on
            tiltEnabled = command.on
            DiagnoseLog.append(this, "体感开关: ${if (command.on) "开" else "关"}")
            pushStatus()
            return
        }
        if (command is Command.Calibrate) {
            startCalibration()
            return
        }
        val a11yReady = ControlAccessibilityService.Access.isReady()
        DiagnoseLog.append(this, "执行指令: ${command.javaClass.simpleName}")
        DiagnoseLog.append(this, "  无障碍服务就绪=$a11yReady")
        if (!a11yReady) {
            DiagnoseLog.append(this, "  ⚠ 无障碍服务未启用，指令会被丢弃！请到系统设置里开启")
        }

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
                is Command.RawAccel -> false
                is Command.SensorState -> false
                Command.Calibrate -> false
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

    /** 收到多少条实时数据。用来区分「手环没发」和「发了但没显示」。 */
    @Volatile
    var tiltCount: Int = 0

    /** 手环传感器是否订阅成功。 */
    @Volatile
    var sensorOk: Boolean = false

    /** 订阅失败原因。 */
    @Volatile
    var sensorMsg: String = ""

    /** 手环当前体感开关状态。 */
    @Volatile
    var tiltEnabled: Boolean = false
        private set

    /** 体感模式是否开启（由手环同步过来）。 */
    @Volatile
    private var tiltOn = false

    private val gestureStore by lazy { com.wristcontrol.app.gesture.GestureStore(this) }
    private val configStore by lazy { com.wristcontrol.app.gesture.GestureConfigStore(this) }

    /** 手势识别引擎，跑在手机端。启动时恢复上次校准的姿势基线。 */
    private val engine by lazy {
        com.wristcontrol.app.gesture.GestureEngine(configStore.load()).also { e ->
            val b = configStore.loadBaseline()
            if (b != null) e.setBaseline(b[0].toDouble(), b[1].toDouble(), b[2].toDouble())
            e.flipLR = configStore.loadFlipLR()
        }
    }

    /** 供界面读实时状态。 */
    fun engineState(): com.wristcontrol.app.gesture.GestureEngine = engine

    /** 面板开关：左右取反（镜像 E/W），立即生效并持久化。 */
    fun setFlipLR(on: Boolean) {
        engine.flipLR = on
        configStore.saveFlipLR(on)
        DiagnoseLog.append(this, "左右取反: " + (if (on) "开" else "关"))
    }

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
        engine.updateConfig(c)
        DiagnoseLog.append(this, "下发配置: 左右死区=${c.deadZoneH}° 上下死区=${c.deadZoneV}° 保持=${c.holdMs}ms")
        transport?.sendRaw(
            com.wristcontrol.app.protocol.StatusCodec.encodeConfigSync(
                deadZoneH = c.deadZoneH,
                deadZoneV = c.deadZoneV,
                holdMs = c.holdMs,
                idleEndMs = c.idleEndMs,
                live = c.showLive,
                rotationOffset = c.rotationOffset,
                tolerance = c.tolerance,
            ),
        )
    }

    /**
     * 识别出手势后，查映射并执行。
     *
     * 识别和映射都在手机端：改算法/映射不用重装 RPK。
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

        // 容错必须用用户设置的值，不然面板上的滑杆是摆设
        val tolerance = configStore.load().tolerance
        val hit = com.wristcontrol.app.gesture.GestureMatcher.bestMatch(actual, templates, tolerance)
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

    // ================= 姿势校准 =================

    private val calLock = Any()
    private var calSamples: MutableList<FloatArray>? = null
    private var calStartAt = 0L
    private val calTimeout = Runnable {
        synchronized(calLock) {
            if (calSamples != null) {
                calSamples = null
                sendCalResult(false, "未收到传感器数据，请先打开体感开关")
                DiagnoseLog.append(this@ControlService, "校准失败：窗口内没有原始帧")
            }
        }
    }

    /** 开始校准：收集约 0.7 秒原始帧，取平均作为零位基线。手环长按体感键或面板按钮触发。 */
    fun startCalibration() {
        synchronized(calLock) {
            if (calSamples != null) return
            calSamples = mutableListOf()
            calStartAt = System.currentTimeMillis()
        }
        handler.removeCallbacks(calTimeout)
        handler.postDelayed(calTimeout, 2500)
        DiagnoseLog.append(this, "开始姿势校准：保持手腕不动…")
    }

    /** 面板按钮用：清除校准，恢复水平面基准。 */
    fun clearCalibration() {
        engine.clearBaseline()
        configStore.clearBaseline()
        DiagnoseLog.append(this, "已清除校准基线")
    }

    private fun collectCalSample(x: Float, y: Float, z: Float) {
        var snapshot: List<FloatArray>? = null
        synchronized(calLock) {
            val list = calSamples ?: return
            list.add(floatArrayOf(x, y, z))
            if (System.currentTimeMillis() - calStartAt >= 700) {
                snapshot = list.toList()
                calSamples = null
            }
        }
        if (snapshot != null) finishCalibration(snapshot!!)
    }

    private fun finishCalibration(samples: List<FloatArray>) {
        handler.removeCallbacks(calTimeout)
        if (samples.size < 10) {
            sendCalResult(false, "数据太少，请打开体感后重试")
            return
        }
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (s in samples) { sx += s[0]; sy += s[1]; sz += s[2] }
        val n = samples.size.toDouble()
        val mx = sx / n; val my = sy / n; val mz = sz / n
        val mg = kotlin.math.hypot(kotlin.math.hypot(mx, my), mz)
        if (mg < 0.01) {
            sendCalResult(false, "传感器读数异常，请重试")
            return
        }
        val bx = mx / mg; val by = my / mg; val bz = mz / mg
        // 稳定性检查：任何一帧偏离均值超过 12° 就说明手在动，拒绝校准
        for (s in samples) {
            val g = kotlin.math.hypot(kotlin.math.hypot(s[0].toDouble(), s[1].toDouble()), s[2].toDouble())
            if (g < 0.01) continue
            val dot = ((s[0] / g) * bx + (s[1] / g) * by + (s[2] / g) * bz).coerceIn(-1.0, 1.0)
            if (Math.toDegrees(Math.acos(dot)) > 12.0) {
                sendCalResult(false, "手在动，端稳一点再校准")
                DiagnoseLog.append(this, "校准失败：采样抖动过大")
                return
            }
        }
        engine.setBaseline(mx, my, mz)
        configStore.saveBaseline(mx.toFloat(), my.toFloat(), mz.toFloat())
        sendCalResult(true, "已校准")
        DiagnoseLog.append(this, "校准成功：基线=(%.3f, %.3f, %.3f) 样本=%d".format(bx, by, bz, samples.size))
    }

    private fun sendCalResult(ok: Boolean, msg: String) {
        try {
            transport?.sendRaw(StatusCodec.encodeCalResult(ok, msg))
        } catch (t: Throwable) {
            Log.w(TAG, "发送校准结果失败: ${t.javaClass.simpleName}")
        }
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

    /**
     * 推一帧状态给手环。
     *
     * @param force 跳过限流。手环重进页面、刚连上时必须立即推一次，
     *              否则手环要等最多 5 秒才收到，期间显示「未连接手机」。
     *
     * 注意：lastHeartbeat 在**发送成功之后**才更新。
     * 之前是先更新再发，transport 未就绪时那次失败也占用了限流窗口，
     * 导致手环端长时间收不到 status。
     */
    private fun pushStatus(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHeartbeat < HEARTBEAT_MS - 500) return

        val tr = transport ?: return

        val battery = try {
            (getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Throwable) {
            -1
        }
        try {
            tr.sendRaw(
                StatusCodec.encode(
                    id = "st-${seq.incrementAndGet()}",
                    link = if (tr.hasNode()) "connected" else "disconnected",
                    battery = battery,
                    listening = ControlAccessibilityService.Access.isReady(),
                    tiltOn = tiltOn,
                ),
            )
            lastHeartbeat = now   // 只有真发出去才计时
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