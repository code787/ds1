package com.wristcontrol.app.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * 手环 → 手机的指令。
 *
 * 每个字段都显式给出，缺省值不猜：手环没发的字段一律当"未指定"处理。
 */
sealed interface Command {
    /** 触摸板相对位移。dx/dy 归一化到 [-1, 1]，由手机端换算成像素。 */
    data class Move(val dx: Float, val dy: Float) : Command

    /** 在触摸板圆点位置单击。 */
    data object Tap : Command

    data object Back : Command
    data object Home : Command
    data object Recents : Command

    /** delta > 0 加音量，< 0 减音量。 */
    data class Volume(val delta: Int) : Command

    data object ScreenOff : Command

    /** 心跳，只刷新存活时间，不执行任何动作。 */
    data object Ping : Command

    /** 手环回执：表示某条状态帧已收到（手机端据此停止重发）。 */
    data class Ack(val refId: String) : Command

    /**
     * 手环识别出某个手势。
     *
     * 手环端只负责识别方向序列，**动作映射由手机端决定**——
     * 这样改映射不用重装 RPK，也不用把用户的操作习惯存在手表上。
     */
    data class GestureHit(val path: String) : Command

    /** 手环请求同步手势模板（刚启动或用户改了映射）。 */
    data object SyncRequest : Command

    /** 手环报告当前体感开关状态。 */
    data class TiltState(val on: Boolean) : Command

    /**
     * 手环实时上报当前识别到的方向（诊断用）。
     *
     * 只在学习/调试时开启，正常使用不上报——每 20ms 一条会把蓝牙打满。
     */
    data class TiltLive(
        val dir: String,
        val path: String,
        /** 原始加速度三轴。手环没有陀螺仪，所以只有加速度计数据。 */
        val ax: Float = 0f,
        val ay: Float = 0f,
        val az: Float = 0f,
        /** 换算后的倾斜角（度）。 */
        val angle: Float = 0f,
    ) : Command

    companion object {
        fun decode(raw: String): Command? = try {
            val o = JSONObject(raw)
            when (o.optString("t")) {
                "move" -> Move(
                    dx = o.optDouble("dx", 0.0).toFloat().coerceIn(-1f, 1f),
                    dy = o.optDouble("dy", 0.0).toFloat().coerceIn(-1f, 1f),
                )
                "tap" -> Tap
                "back" -> Back
                "home" -> Home
                "recents" -> Recents
                "vol" -> Volume(o.optInt("delta", 0).coerceIn(-1, 1))
                "screenoff" -> ScreenOff
                "ping" -> Ping
                "ack" -> Ack(o.optString("refId"))
                "gesture" -> GestureHit(o.optString("path"))
                "syncreq" -> SyncRequest
                "tilt" -> TiltState(o.optBoolean("on", false))
                "tiltlive" -> TiltLive(
                    dir = o.optString("dir"),
                    path = o.optString("path"),
                    // 这四个字段之前漏了——只解析 dir/path，
                    // 导致手环明明发了加速度，手机端拿到的永远是 0。
                    ax = o.optDouble("ax", 0.0).toFloat(),
                    ay = o.optDouble("ay", 0.0).toFloat(),
                    az = o.optDouble("az", 0.0).toFloat(),
                    angle = o.optDouble("angle", 0.0).toFloat(),
                )
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * 手机 → 手环的状态帧。
 *
 * 只带连接状态、电量与手势服务是否就绪；**不带任何屏幕内容**。
 * id 用于让手环回执，手机据此判断链路是否真的活着（SDK 的 Task 成功不算送达）。
 */
object StatusCodec {
    fun encode(
        id: String,
        link: String,
        battery: Int,
        listening: Boolean,
        tiltOn: Boolean = false,
    ): ByteArray =
        JSONObject().apply {
            put("v", 1)
            put("kind", "status")
            put("id", id)
            put("link", link)
            put("battery", battery)
            put("listening", listening)
            put("tilt", tiltOn)
        }.toString().toByteArray(Charsets.UTF_8)

    /** 手机 → 手环：下发手势识别配置（死区、时长等）。 */
    fun encodeConfigSync(
        deadZone: Int,
        holdMs: Int,
        idleEndMs: Int,
        live: Boolean,
        rotationOffset: Int = 0,
        tolerance: Int = 1,
    ): ByteArray =
        JSONObject().apply {
            put("v", 1)
            put("kind", "cfg")
            put("deadZone", deadZone)
            put("holdMs", holdMs)
            put("idleEndMs", idleEndMs)
            put("live", live)
            put("rotationOffset", rotationOffset)
            // 容错距离也必须下发，否则界面上的滑杆是摆设
            put("tolerance", tolerance)
        }.toString().toByteArray(Charsets.UTF_8)

    /** 手机 → 手环：下发手势模板列表。 */
    fun encodeGestureSync(gestures: List<com.wristcontrol.app.gesture.Gesture>): ByteArray =
        JSONObject().apply {
            put("v", 1)
            put("kind", "gsync")
            val arr = JSONArray()
            for (g in gestures) {
                arr.put(JSONObject().apply {
                    put("id", g.id)
                    put("name", g.name)
                    put("path", com.wristcontrol.app.gesture.GestureCodec.encodePath(g.path))
                })
            }
            put("list", arr)
        }.toString().toByteArray(Charsets.UTF_8)

    /** 手机 → 手环的确认帧，用于告诉手环某条指令已执行。 */
    fun encodeAck(refId: String): ByteArray =
        JSONObject().apply {
            put("v", 1)
            put("kind", "ack")
            put("refId", refId)
        }.toString().toByteArray(Charsets.UTF_8)
}