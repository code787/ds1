package com.wristcontrol.app.input

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 唯一的无障碍服务。它只做两件事：
 * 1. 持有一个 [GestureExecutor]，供前台服务调用；
 * 2. 提供"服务是否已启用"的静态查询，让主页能显示真实状态。
 *
 * 它不读取窗口内容（配置里 canRetrieveWindowContent=false），也不监听事件内容。
 */
class ControlAccessibilityService : AccessibilityService() {

    /** 供前台服务取用的手势执行器；服务未启用时为 null。 */
    val gestures: GestureExecutor by lazy { GestureExecutor(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 刻意留空：本服务不需要任何事件内容。
    }

    override fun onInterrupt() {
        Log.i(TAG, "无障碍服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "无障碍服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * 全局访问点。
     *
     * 注意：Kotlin 一个类只能有一个 companion object，所以实例状态与静态查询
     * 必须放在同一个 companion 里——早先拆成两个会直接编译失败。
     */
    companion object Access {
        private const val TAG = "WristA11y"

        @Volatile
        private var instance: ControlAccessibilityService? = null

        /** 当前是否有可用的无障碍服务实例。 */
        fun isReady(): Boolean = instance != null

        /** 取当前实例；调用方需自行处理 null。 */
        fun current(): ControlAccessibilityService? = instance
    }
}
