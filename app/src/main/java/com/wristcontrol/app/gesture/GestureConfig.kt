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
    /**
     * 左右方向（E/W）的死区角度（度）。
     *
     * 为什么分开：手环横戴时，左右倾斜和上下倾斜的物理幅度不一样——
     * 手腕左右转动的角度范围比上下抬压小得多，用同一个死区会导致
     * 一边太灵敏、一边没反应。
     */
    val deadZoneH: Int = 18,

    /**
     * 上下方向（N/S）的死区角度（度）。
     */
    val deadZoneV: Int = 18,

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
     * 手环**横向佩戴**（屏幕长边水平）时，加速度计的 x/y 轴相对
     * "用户以为的上下左右"整体偏了 90 度：
     * 用户觉得在"上下"倾，实际读到的是 x 轴变化 → 被识别成左右。
     *
     * 所以默认 90。如果换成竖着戴，改成 0。
     */
    val rotationOffset: Int = 90,
) {
    fun clamp(): GestureConfig = copy(
        deadZoneH = deadZoneH.coerceIn(4, 60),
        deadZoneV = deadZoneV.coerceIn(4, 60),
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
        deadZoneH = prefs.getInt("deadZoneH", 18),
        deadZoneV = prefs.getInt("deadZoneV", 18),
        holdMs = prefs.getInt("holdMs", 120),
        idleEndMs = prefs.getInt("idleEndMs", 600),
        tolerance = prefs.getInt("tolerance", 1),
        showLive = prefs.getBoolean("showLive", true),
        rotationOffset = prefs.getInt("rotationOffset", 90),
    ).clamp()

    fun save(cfg: GestureConfig) {
        val c = cfg.clamp()
        prefs.edit()
            .putInt("deadZoneH", c.deadZoneH)
            .putInt("deadZoneV", c.deadZoneV)
            .putInt("holdMs", c.holdMs)
            .putInt("idleEndMs", c.idleEndMs)
            .putInt("tolerance", c.tolerance)
            .putBoolean("showLive", c.showLive)
            .putInt("rotationOffset", c.rotationOffset)
            .apply()
    }

    // ---- 校准基线（只存手机，不下发手环——识别在手机端） ----

    /** 读取校准基线；未校准返回 null。 */
    fun loadBaseline(): FloatArray? {
        if (!prefs.getBoolean("calOn", false)) return null
        return floatArrayOf(
            prefs.getFloat("calX", 0f),
            prefs.getFloat("calY", 0f),
            prefs.getFloat("calZ", 1f),
        )
    }

    fun saveBaseline(x: Float, y: Float, z: Float) {
        prefs.edit()
            .putBoolean("calOn", true)
            .putFloat("calX", x)
            .putFloat("calY", y)
            .putFloat("calZ", z)
            .apply()
    }

    fun clearBaseline() {
        prefs.edit().putBoolean("calOn", false).apply()
    }
}