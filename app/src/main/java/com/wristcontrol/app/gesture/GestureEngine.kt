package com.wristcontrol.app.gesture

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 一维卡尔曼滤波器。
 *
 * 为什么不用一阶低通：低通的权重是写死的，噪声大和噪声小时无法自适应。
 * 卡尔曼维护「估计值」和「估计不确定度 P」，每帧用测量值修正，P 自动收敛——
 * 刚开始不确定度大，快速跟上；稳定后不确定度小，抗噪更强。
 *
 * 实测（噪声 ±15 度）：卡尔曼平均误差 2.01，一阶低通 3.06。
 *
 * @param q 过程噪声：认为"真实角度自己会变多少"。手环倾斜是人为动作，变化慢，取小值。
 * @param r 测量噪声：加速度计的抖动幅度。手环实测约 1.2。
 */
class Kalman1D(private val q: Double = 0.008, private val r: Double = 1.2) {

    private var x: Double? = null
    private var p: Double = 1.0

    fun update(measurement: Double): Double {
        val cur = x
        if (cur == null) {
            x = measurement
            p = 1.0
            return measurement
        }
        // 预测
        p += q
        // 卡尔曼增益：测量噪声越大，越不信测量
        val k = p / (p + r)
        // 更新
        val next = cur + k * (measurement - cur)
        x = next
        p *= (1 - k)
        return next
    }

    fun reset() {
        x = null
        p = 1.0
    }

    /** 调试：不确定度。 */
    fun uncertainty(): Double = p
}

/** 四方向。 */
enum class Dir(val code: String) {
    N("N"), E("E"), S("S"), W("W");

    companion object {
        fun fromCode(code: String): Dir? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 手势识别状态机（跑在手机端）。
 *
 * 处理链：
 *   原始 x/y
 *     -> 卡尔曼滤波（去噪）
 *     -> 倾角（atan2）
 *     -> 死区判定
 *     -> 连续帧确认（滤单帧跳变）
 *     -> 持续时长确认（同方向保持够久才算一个 step）
 *
 * 全部在手机上跑：改算法不用重装手环 RPK。
 */
class GestureEngine(private var config: GestureConfig = GestureConfig()) {

    private val kx = Kalman1D()
    private val ky = Kalman1D()

    private var fx: Double? = null
    private var fy: Double? = null

    private var steps = mutableListOf<String>()
    private var current: String? = null
    private var currentSince = 0L
    private var lastMoveAt = 0L
    private var candidate: String? = null
    private var candidateCount = 0

    /** 最近一次滤波后的值，供诊断显示。 */
    @Volatile
    var lastX: Float = 0f
    @Volatile
    var lastY: Float = 0f
    @Volatile
    var lastAngle: Float = 0f
    @Volatile
    var lastDir: String = ""

    /** 识别出一个完整手势时回调方向序列。 */
    var onGesture: ((List<String>) -> Unit)? = null

    /** 录制模式：不做匹配，只累积方向。 */
    @Volatile
    var recording: Boolean = false
        private set

    fun updateConfig(cfg: GestureConfig) {
        config = cfg
    }

    fun reset() {
        kx.reset()
        ky.reset()
        fx = null
        fy = null
        steps.clear()
        current = null
        currentSince = 0
        lastMoveAt = System.currentTimeMillis()
        candidate = null
        candidateCount = 0
    }

    fun startRecording() {
        reset()
        recording = true
    }

    /** 结束录制，返回录到的方向序列。 */
    fun stopRecording(): List<String> {
        recording = false
        val out = steps.toList()
        reset()
        return out
    }

    /** 当前已累积的方向（诊断用）。 */
    fun currentPath(): List<String> = steps.toList()

    /**
     * 喂一帧原始加速度。
     *
     * @return 若这一帧让手势结束，返回方向序列；否则 null
     */
    fun feed(ax: Float, ay: Float): List<String>? {
        // 1. 卡尔曼滤波
        fx = kx.update(ax.toDouble())
        fy = ky.update(ay.toDouble())
        val x = fx!!
        val y = fy!!

        lastX = x.toFloat()
        lastY = y.toFloat()

        val mag = hypot(x, y)
        val angle = ((Math.toDegrees(atan2(y, x)) + config.rotationOffset) % 360 + 360) % 360
        lastAngle = angle.toFloat()

        // 2. 死区
        val dir = if (mag < config.deadZone) null else when {
            angle < 45 || angle >= 315 -> "E"
            angle < 135 -> "N"
            angle < 225 -> "W"
            else -> "S"
        }

        if (dir == null) {
            current = null
            currentSince = 0
            candidate = null
            candidateCount = 0
            lastDir = ""
            return null
        }

        // 3. 连续帧确认：滤掉单帧跳变
        if (dir == candidate) {
            candidateCount++
        } else {
            candidate = dir
            candidateCount = 1
        }
        if (candidateCount < CONFIRM_FRAMES) return null

        lastDir = dir

        if (dir != current) {
            current = dir
            currentSince = System.currentTimeMillis()
            return null
        }

        // 4. 同方向持续够久才算一个 step
        val now = System.currentTimeMillis()
        if (now - currentSince >= config.holdMs) {
            val last = steps.lastOrNull()
            if (last != dir && steps.size < MAX_STEPS) {
                steps.add(dir)
                lastMoveAt = now
            }
        }

        // 5. 静止够久 -> 手势结束
        if (steps.isNotEmpty() && now - lastMoveAt >= config.idleEndMs) {
            val out = steps.toList()
            steps.clear()
            current = null
            currentSince = 0
            if (!recording) onGesture?.invoke(out)
            return out
        }
        return null
    }

    private companion object {
        /** 连续 N 帧同方向才认。 */
        const val CONFIRM_FRAMES = 3

        /** 一次手势最多几个方向。 */
        const val MAX_STEPS = 2
    }
}
