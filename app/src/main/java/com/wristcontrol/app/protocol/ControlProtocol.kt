package com.wristcontrol.app.protocol

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
    fun encode(id: String, link: String, battery: Int, listening: Boolean): ByteArray =
        JSONObject().apply {
            put("v", 1)
            put("kind", "status")
            put("id", id)
            put("link", link)
            put("battery", battery)
            put("listening", listening)
        }.toString().toByteArray(Charsets.UTF_8)

    /** 手机 → 手环的确认帧，用于告诉手环某条指令已执行。 */
    fun encodeAck(refId: String): ByteArray =
        JSONObject().apply {
            put("v", 1)
            put("kind", "ack")
            put("refId", refId)
        }.toString().toByteArray(Charsets.UTF_8)
}
