package com.wristcontrol.app.gesture

import android.content.Context

/**
 * 手势识别的可调参数。
 *
 * 这些值直接决定手感，必须能在 App 上改：
 * - 手环戴的位置、手腕初始姿态因人而异
 * - 同一个死区角度，有人觉得太灵敏、有人觉得没反应
 *
 * 参数存在手机端，同步给手环。改完不用重装 RPK。
 */
data class GestureConfig(
    /** 死区角度（度）。倾斜小于这个值视为没动。越小越灵敏。 */
    val deadZone: Int = 12,

    /** 同一方向需持续多久才确认（毫秒）。越小越跟手，但抖动变多。 */
    val holdMs: Int = 120,

    /** 静止多久判定手势结束（毫秒）。 */
    val idleEndMs: Int = 600,

    /** 匹配容错距离。0=必须完全一致，1=允许差一个方向。 */
    val tolerance: Int = 1,

    /** 是否在手机上实时显示识别到的方向（诊断用）。 */
    val showLive: Boolean = true,

    /**
     * 坐标系旋转偏移（度，-180~180）。
     *
     * 手环戴在手腕上时，屏幕是立着的、还带角度，加速度计的 x/y 轴
     * 跟"用户以为的上下左右"对不上。这个值用来把坐标系转正。
     *
     * 典型情况：向右倾却识别成右上 → 说明整体偏了 45 度左右，调到 -45 试试。
     */
    val rotationOffset: Int = 0,
) {
    fun clamp(): GestureConfig = copy(
        deadZone = deadZone.coerceIn(4, 40),
        holdMs = holdMs.coerceIn(40, 500),
        idleEndMs = idleEndMs.coerceIn(200, 2000),
        tolerance = tolerance.coerceIn(0, 2),
        rotationOffset = rotationOffset.coerceIn(-180, 180),
    )
}

/** 配置的读写。 */
class GestureConfigStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("wristcontrol_gesture_cfg", Context.MODE_PRIVATE)

    fun load(): GestureConfig = GestureConfig(
        deadZone = prefs.getInt("deadZone", 12),
        holdMs = prefs.getInt("holdMs", 120),
        idleEndMs = prefs.getInt("idleEndMs", 600),
        tolerance = prefs.getInt("tolerance", 1),
        showLive = prefs.getBoolean("showLive", true),
        rotationOffset = prefs.getInt("rotationOffset", 0),
    ).clamp()

    fun save(cfg: GestureConfig) {
        val c = cfg.clamp()
        prefs.edit()
            .putInt("deadZone", c.deadZone)
            .putInt("holdMs", c.holdMs)
            .putInt("idleEndMs", c.idleEndMs)
            .putInt("tolerance", c.tolerance)
            .putBoolean("showLive", c.showLive)
            .putInt("rotationOffset", c.rotationOffset)
            .apply()
    }
}