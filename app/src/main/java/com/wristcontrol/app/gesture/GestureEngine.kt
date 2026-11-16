package com.wristcontrol.app.gesture

/**
 * 八方向编码。
 *
 * 用手环的**绝对倾角**而不是位置——加速度计二次积分求位移会漂移，
 * 但倾角是绝对测量，稳定可靠。体验上等同鼠标手势，输入方式从"拖动"变成"倾斜"。
 */
enum class Dir(val code: String) {
    N("N"), NE("NE"), E("E"), SE("SE"),
    S("S"), SW("SW"), W("W"), NW("NW");

    companion object {
        fun fromCode(code: String): Dir? = entries.firstOrNull { it.code == code }
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

    /** 手势模板的传输格式（手机 → 手环），只传五个字段。 */
    fun toJson(g: Gesture): String {
        val p = encodePath(g.path)
        return "{\"id\":\"" + g.id + "\",\"name\":\"" + g.name +
            "\",\"path\":\"" + p + "\",\"action\":\"" + g.action +
            "\",\"arg\":\"" + g.arg + "\"}"
    }
}

/**
 * 手势匹配器。
 *
 * 用编辑距离而不是完全相等——真人画手势不可能每次都一模一样，
 * 允许漏一个方向或多一个抖动。
 */
object GestureMatcher {

    /** 编辑距离（Levenshtein），容忍方向序列的细微差异。 */
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
     *
     * 返回 null 表示没有足够接近的模板——**宁可不动，也不猜**。
     * 手环端误触一次（莫名返回上一页）比没反应更让人恼火。
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

/**
 * 把倾角映射到八方向。
 *
 * 坐标系：手环屏幕朝上时，x 向右、y 向上。
 * 倾斜角度超过死区才算某个方向，否则返回 null（防抖）。
 */
object TiltMapper {

    /** 死区角度（度）。小于这个角度视为"没有倾斜"。 */
    const val DEAD_ZONE = 12f

    /**
     * @param x 左右倾斜，正值向右
     * @param y 前后倾斜，正值向上（抬手）
     * @return 方向；在死区内返回 null
     */
    fun toDir(x: Float, y: Float): Dir? {
        val magnitude = kotlin.math.hypot(x, y)
        if (magnitude < DEAD_ZONE) return null
        val angle = Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble()))
        val normalized = ((angle % 360) + 360) % 360
        return when {
            normalized < 22.5 || normalized >= 337.5 -> Dir.E
            normalized < 67.5 -> Dir.NE
            normalized < 112.5 -> Dir.N
            normalized < 157.5 -> Dir.NW
            normalized < 202.5 -> Dir.W
            normalized < 247.5 -> Dir.SW
            normalized < 292.5 -> Dir.S
            else -> Dir.SE
        }
    }
}
