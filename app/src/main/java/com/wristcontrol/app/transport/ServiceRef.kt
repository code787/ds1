package com.wristcontrol.app.transport

/**
 * 当前运行中的 ControlService 引用。
 *
 * 界面需要读实时方向、下发配置，但不能直接持有 Service 对象。
 * 这里用静态引用，在 Service 的 onCreate/onDestroy 里维护。
 * 只暴露只读访问，避免界面误改 Service 状态。
 */
object ServiceRef {
    @Volatile
    var current: ControlService? = null
        private set

    fun attach(service: ControlService) {
        current = service
    }

    fun detach(service: ControlService) {
        if (current === service) current = null
    }
}
