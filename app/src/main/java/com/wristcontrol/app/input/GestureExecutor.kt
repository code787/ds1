package com.wristcontrol.app.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log

/**
 * 把协议指令翻译成无障碍手势。
 *
 * 设计约束：
 * - 所有手势都带超时保护，失败只记日志，绝不抛给调用方（前台服务不能因为一次手势失败就崩）。
 * - 屏幕尺寸**每次现取**：构造时缓存 widthPixels 会在旋转后失效。
 * - 手势落点始终夹在屏幕内，避免滑出边界被判为无效。
 */
class GestureExecutor(private val service: AccessibilityService) {

    private companion object {
        const val TAG = "WristGesture"
        /** 一次触摸板滑动的最大像素跨度占屏幕短边的比例。 */
        const val MAX_SWIPE_RATIO = 0.55f
        /** 滑动动画时长；太短会被系统当点击，太长手环上会有延迟感。 */
        const val SWIPE_DURATION_MS = 60L
        const val TAP_DURATION_MS = 40L
        /** 手势落点与屏幕边缘的安全距离。 */
        const val EDGE_MARGIN = 8f
    }

    /** 屏幕宽高：每次调用现取，避免旋转后使用过期值。 */
    private fun screenW(): Float = service.resources.displayMetrics.widthPixels.toFloat()
    private fun screenH(): Float = service.resources.displayMetrics.heightPixels.toFloat()

    fun swipe(dx: Float, dy: Float): Boolean {
        if (dx == 0f && dy == 0f) return false

        val w = screenW()
        val h = screenH()
        if (w <= 0f || h <= 0f) {
            Log.w(TAG, "屏幕尺寸无效: ${w}x${h}")
            return false
        }

        val span = minOf(w, h) * MAX_SWIPE_RATIO
        val startX = w / 2f
        val startY = h / 2f
        val endX = (startX + dx * span).coerceIn(EDGE_MARGIN, w - EDGE_MARGIN)
        val endY = (startY + dy * span).coerceIn(EDGE_MARGIN, h - EDGE_MARGIN)

        // 起点与终点重合会被系统判为无效手势，直接跳过
        if (startX == endX && startY == endY) return false

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return dispatch(path, SWIPE_DURATION_MS)
    }

    fun tap(): Boolean {
        val w = screenW()
        val h = screenH()
        if (w <= 0f || h <= 0f) return false
        val path = Path().apply { moveTo(w / 2f, h / 2f) }
        return dispatch(path, TAP_DURATION_MS)
    }

    private fun dispatch(path: Path, durationMs: Long): Boolean = try {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = service.dispatchGesture(gesture, null, null)
        if (!ok) Log.w(TAG, "dispatchGesture 返回 false（可能被系统拒绝或屏幕已锁定）")
        ok
    } catch (t: Throwable) {
        Log.w(TAG, "dispatchGesture 抛出: ${t.javaClass.simpleName} ${t.message}")
        false
    }
}
