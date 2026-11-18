package com.wristcontrol.app.gesture

import kotlin.math.atan2
import kotlin.math.acos
import kotlin.math.asin
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

    /**
     * 校准基线：用户舒适姿势下的重力单位向量。
     *
     * 未校准时以水平面为零位——但手腕自然放着本来就是倾斜的，
     * 「放松」不等于「零位」，怎么放都别扭。校准后以基线为零位：
     * 倾角 = 当前重力向量与基线的夹角，方向 = 相对基线的偏移方向。
     * 基线持久化在手机（GestureConfigStore），重启不丢。
     */
    private var calibrated = false
    private var baseX = 0.0
    private var baseY = 0.0
    private var baseZ = 1.0

    /** 记录校准基线（任意长度向量，内部归一化）。 */
    fun setBaseline(x: Double, y: Double, z: Double) {
        val g = hypot(hypot(x, y), z)
        if (g < 0.01) return
        baseX = x / g
        baseY = y / g
        baseZ = z / g
        calibrated = true
        reset()
    }

    /** 清除校准，恢复水平面基准。 */
    fun clearBaseline() {
        calibrated = false
        reset()
    }

    fun isCalibrated(): Boolean = calibrated

    /** 最近一次滤波后的值，供诊断显示。 */
    @Volatile
    var lastX: Float = 0f
    @Volatile
    var lastY: Float = 0f
    @Volatile
    var lastAngle: Float = 0f

    /** 倾角（度）。未校准=相对水平面（0=平放）；已校准=相对校准姿势（0=回到零位）。 */
    var lastTilt: Float = 0f
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
    fun feed(ax: Float, ay: Float, az: Float = 0f): List<String>? {
        // 1. 卡尔曼滤波
        fx = kx.update(ax.toDouble())
        fy = ky.update(ay.toDouble())
        val x = fx!!
        val y = fy!!

        lastX = x.toFloat()
        lastY = y.toFloat()

        // 倾角计算：加速度计读数是重力在三个轴上的投影（g 单位）。
        // 只用 hypot(x,y) 判断倾斜是错的——那最大才 1.4，
        // 而 deadZone 是角度（18 度），永远小于它，方向永远 null。
        //
        // 正确做法：用重力向量算「相对水平面的倾角」
        //   tiltFrac = hypot(x,y) / hypot(x,y,z)   （0=平放，1=竖直）
        //   tiltDeg  = asin(tiltFrac) 转角度
        // 这样 deadZone 才是真正的「倾斜多少度才算动」。
        val gTotal = hypot(hypot(x, y), az.toDouble())
        val tiltDeg: Double
        val angle: Double
        if (calibrated && gTotal > 0.01) {
            // 校准模式：零位 = 用户校准时的姿势，不是水平面。
            // 倾角 = 当前重力方向与基线方向的夹角（度），松手回到基线即回零。
            val cx = x / gTotal
            val cy = y / gTotal
            val cz = az.toDouble() / gTotal
            val dot = (cx * baseX + cy * baseY + cz * baseZ).coerceIn(-1.0, 1.0)
            tiltDeg = Math.toDegrees(acos(dot))
            // 方向 = 相对基线的偏移在 x/y 平面上的指向。
            // 偏移太小时方向不可靠，沿用上一帧角度（此时倾角也小，出不了死区）。
            val dx = cx - baseX
            val dy = cy - baseY
            angle = if (hypot(dx, dy) < 0.02) {
                lastAngle.toDouble()
            } else {
                ((Math.toDegrees(atan2(dy, dx)) + config.rotationOffset) % 360 + 360) % 360
            }
        } else {
            // 未校准：以水平面为零位（旧行为，保证没校准也能用）
            val tiltFrac = if (gTotal > 0.01) hypot(x, y) / gTotal else 0.0
            tiltDeg = Math.toDegrees(asin(tiltFrac.coerceIn(0.0, 1.0)))
            angle = ((Math.toDegrees(atan2(y, x)) + config.rotationOffset) % 360 + 360) % 360
        }
        lastAngle = angle.toFloat()
        lastTilt = tiltDeg.toFloat()

        val now = System.currentTimeMillis()

        // 2. 死区判定（单位：度）
        // 左右和上下用不同阈值：手环横戴时两个方向的物理幅度不一样。
        // 先按角度判断大概是哪个方向，再用对应方向的死区。
        val rawDir = when {
            angle < 45 || angle >= 315 -> "E"
            angle < 135 -> "N"
            angle < 225 -> "W"
            else -> "S"
        }
        val zone = if (rawDir == "E" || rawDir == "W") config.deadZoneH else config.deadZoneV
        val dir = if (tiltDeg < zone) null else rawDir

        if (dir == null) {
            // 回到死区：重置方向追踪，但**不能提前 return**——
            // 手势结束检测必须照样执行，否则 steps 永远提交不了。
            current = null
            currentSince = 0
            candidate = null
            candidateCount = 0
            lastDir = ""
        } else {
            // 3. 连续帧确认：滤掉单帧跳变
            if (dir == candidate) {
                candidateCount++
            } else {
                candidate = dir
                candidateCount = 1
            }

            if (candidateCount >= CONFIRM_FRAMES) {
                lastDir = dir

                if (dir != current) {
                    current = dir
                    currentSince = now
                } else if (now - currentSince >= config.holdMs) {
                    // 4. 同方向持续够久才算一个 step
                    val last = steps.lastOrNull()
                    if (last != dir && steps.size < MAX_STEPS) {
                        steps.add(dir)
                        lastMoveAt = now
                    }
                }
            }
        }

        // 5. 手势结束判定——**不管有没有倾斜都要查**
        if (steps.isNotEmpty() && now - lastMoveAt >= config.idleEndMs) {
            val out = steps.toList()
            steps.clear()
            current = null
            currentSince = 0
            candidate = null
            candidateCount = 0
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

/**
 * 一条已学习的手势：方向序列 + 绑定的操作。
 *
 * 方向序列最多两个（如 "E,S"），两两组合已经够用，
 * 更长的序列难画准、误识别率高。
 */
data class Gesture(
    val id: String,
    val name: String,
    /** 方向序列，如 ["E", "S"]。 */
    val path: List<String>,
    /** 触发的指令类型，与 Command 的 t 字段对应。 */
    val action: String,
    /** 动作参数（比如音量 delta），无参数时为空串。 */
    val arg: String = "",
)

object GestureCodec {
    /** 序列化：["E","S"] -> "E,S" */
    fun encodePath(path: List<String>): String = path.joinToString(",")

    /** 反序列化：忽略空串与未知方向。 */
    fun decodePath(text: String?): List<String> =
        text.orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && Dir.fromCode(it) != null }

}

/**
 * 手势匹配器。
 *
 * 用编辑距离而不是完全相等——真人画手势不可能每次都一模一样，
 * 允许漏一个方向或多一个抖动。
 */
object GestureMatcher {

    /** 编辑距离（Levenshtein）。 */
    fun distance(a: List<String>, b: List<String>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        val prev = IntArray(b.size + 1) { it }
        val cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return prev[b.size]
    }

    /**
     * 在模板里找最匹配的一条。
     * 返回 null 表示没有足够接近的——宁可不动，也不猜。
     */
    fun bestMatch(actual: List<String>, templates: List<Gesture>, maxDistance: Int = 1): Gesture? {
        if (actual.isEmpty() || templates.isEmpty()) return null
        var best: Gesture? = null
        var bestDist = Int.MAX_VALUE
        for (g in templates) {
            val d = distance(actual, g.path)
            if (d < bestDist) {
                bestDist = d
                best = g
            }
        }
        return if (bestDist <= maxDistance) best else null
    }
}