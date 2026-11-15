package com.wristcontrol.app.transport

import android.content.Context
import android.util.Log
import com.wristcontrol.app.protocol.Command

/**
 * 小米官方 interconnect SDK 1.4 的封装。
 *
 * 关键设计：**SDK 的每一次调用都可能因为环境缺失而抛异常**。
 * 常见原因：没装小米运动健康、SDK 版本不匹配、服务未绑定。
 * 所有入口都包 try-catch，失败只记日志并回调 onError，绝不把异常抛给前台服务
 * （前台服务一崩，整个桥接就没了，用户看到的就是"闪退"）。
 *
 * 调用约束来自官方示例与导航环的实测：
 * - 没有 nodeId 时绝不能调用 SDK（传空 id 是伪造结果）；
 * - sendMessage 的 Task 成功只代表本地通道收下了，不代表手环收到；
 * - 失败回调给的是 Exception，不是 Status，不要调 getCode()。
 */
class XiaomiWearableTransport(private val context: Context) {

    private companion object {
        const val TAG = "WristTransport"
    }

    /** SDK 是否可用。不可用时所有操作直接返回失败，不再尝试。 */
    @Volatile
    private var sdkAvailable = false

    @Volatile
    private var nodeId: String? = null

    @Volatile
    private var listeningNodeId: String? = null

    private var onCommand: ((Command) -> Unit)? = null
    private var onStatus: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    // 延迟初始化：SDK 类在类加载时就会触发，必须包起来
    private val nodeApi by lazy { runCatching { com.xiaomi.xms.wearable.Wearable.getNodeApi(context) }.getOrNull() }
    private val authApi by lazy { runCatching { com.xiaomi.xms.wearable.Wearable.getAuthApi(context) }.getOrNull() }
    private val messageApi by lazy { runCatching { com.xiaomi.xms.wearable.Wearable.getMessageApi(context) }.getOrNull() }

    private val listener = com.xiaomi.xms.wearable.message.OnMessageReceivedListener { _, payload ->
        val handler = onCommand ?: return@OnMessageReceivedListener
        val text = try {
            String(payload, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "payload 解码失败: ${t.message}")
            return@OnMessageReceivedListener
        }
        val command = Command.decode(text)
        if (command == null) {
            Log.w(TAG, "无法识别的指令: $text")
            return@OnMessageReceivedListener
        }
        try {
            handler(command)
        } catch (t: Throwable) {
            Log.w(TAG, "处理指令失败: ${t.javaClass.simpleName}")
        }
    }

    fun hasNode(): Boolean = !nodeId.isNullOrEmpty()

    /** 发现手环节点并注册接收监听。任何失败都通过 onError 报告，不抛异常。 */
    fun start(onCommand: (Command) -> Unit, onStatus: () -> Unit, onError: (String) -> Unit) {
        this.onCommand = onCommand
        this.onStatus = onStatus
        this.onError = onError

        if (nodeApi == null || authApi == null || messageApi == null) {
            fail("小米互联 SDK 初始化失败：未安装小米运动健康或版本不匹配")
            return
        }
        sdkAvailable = true
        refreshNode { resolved ->
            if (resolved == null) {
                fail("没有找到已连接的手环")
                return@refreshNode
            }
            requestPermission { granted ->
                if (granted) {
                    startListening(resolved)
                    safeStatus()
                } else {
                    fail("未获得设备管理权限。请确认：1) 小米运动健康已安装并登录 2) 手环已连接 3) 弹出授权框时点允许")
                }
            }
        }
    }

    fun stop() {
        try {
            stopListening()
        } catch (t: Throwable) {
            Log.w(TAG, "stopListening 异常: ${t.javaClass.simpleName}")
        }
        onCommand = null
        onStatus = null
        onError = null
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        try {
            onError?.invoke(message)
        } catch (t: Throwable) {
            Log.w(TAG, "onError 回调异常: ${t.javaClass.simpleName}")
        }
    }

    private fun safeStatus() {
        try {
            onStatus?.invoke()
        } catch (t: Throwable) {
            Log.w(TAG, "onStatus 回调异常: ${t.javaClass.simpleName}")
        }
    }

    private fun refreshNode(onResult: (String?) -> Unit) {
        val api = nodeApi
        if (api == null) {
            onResult(null)
            return
        }
        try {
            api.getConnectedNodes()
                .addOnSuccessListener { nodes: List<com.xiaomi.xms.wearable.node.Node>? ->
                    val first = nodes?.firstOrNull()
                    nodeId = first?.id
                    Log.i(TAG, "节点: ${first?.name ?: "(无)"} id=${nodeId ?: "(空)"}")
                    onResult(nodeId)
                }
                .addOnFailureListener { error ->
                    nodeId = null
                    Log.w(TAG, "getConnectedNodes 失败: ${error.javaClass.simpleName} ${error.message}")
                    onResult(null)
                }
        } catch (t: Throwable) {
            nodeId = null
            Log.w(TAG, "getConnectedNodes 抛出: ${t.javaClass.simpleName}")
            onResult(null)
        }
    }

    /**
     * 自定义消息通道需要 DEVICE_MANAGER 权限。
     *
     * 注意 SDK 的两个 Task 返回类型不同：
     * - checkPermission 返回 Boolean（是否已授予）
     * - requestPermission 返回 Array<Permission>（本次被授予的权限列表）
     * 把后者当 Boolean 用会编译失败：Operator '==' cannot be applied to Array<Permission> and Boolean。
     */
    private fun requestPermission(onResult: (Boolean) -> Unit) {
        val target = nodeId ?: return onResult(false)
        val api = authApi ?: return onResult(false)

        // SDK 的 Task 回调不保证在主线程，而 requestPermission 会弹系统授权对话框。
        // 统一切回主线程，避免"对话框没弹出来却报了未授予"。
        val main = android.os.Handler(android.os.Looper.getMainLooper())

        try {
            api.checkPermission(target, com.xiaomi.xms.wearable.auth.Permission.DEVICE_MANAGER)
                .addOnSuccessListener { already ->
                    if (already == true) {
                        Log.i(TAG, "设备管理权限：已授予")
                        main.post { onResult(true) }
                    } else {
                        Log.i(TAG, "设备管理权限未授予，发起请求（手机应弹出授权框）")
                        main.post {
                            try {
                                api.requestPermission(target, com.xiaomi.xms.wearable.auth.Permission.DEVICE_MANAGER)
                                    .addOnSuccessListener { grantedList ->
                                        // 官方示例的判断：返回列表非空即视为授予。
                                        // 逐个比对枚举会因为 SDK 返回的实例不同而误判。
                                        val ok = grantedList != null && grantedList.isNotEmpty()
                                        Log.i(TAG, "requestPermission 返回 ${grantedList?.size ?: 0} 项 -> ${if (ok) "已授予" else "未授予"}")
                                        main.post { onResult(ok) }
                                    }
                                    .addOnFailureListener { error ->
                                        Log.w(TAG, "requestPermission 失败: ${error.javaClass.simpleName} ${error.message}")
                                        main.post { onResult(false) }
                                    }
                            } catch (t: Throwable) {
                                Log.w(TAG, "requestPermission 抛出: ${t.javaClass.simpleName}")
                                main.post { onResult(false) }
                            }
                        }
                    }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "checkPermission 失败: ${error.javaClass.simpleName} ${error.message}")
                    // 把原始异常名带上，否则用户只看到"未获得权限"无法判断是 SDK 报错还是真没授权
                    main.post { onError?.invoke("权限检查失败：${error.javaClass.simpleName}") }
                    main.post { onResult(false) }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "权限检查抛出: ${t.javaClass.simpleName}")
            onResult(false)
        }
    }

    private fun startListening(target: String) {
        if (listeningNodeId == target) return
        val api = messageApi ?: return
        val previous = listeningNodeId
        listeningNodeId = target
        try {
            if (previous != null && previous != target) {
                api.removeListener(previous)
            }
            api.addListener(target, listener)
                .addOnFailureListener { error ->
                    Log.w(TAG, "addListener 失败: ${error.message}")
                }
        } catch (t: Throwable) {
            listeningNodeId = previous
            Log.w(TAG, "addListener 抛出: ${t.javaClass.simpleName}")
        }
    }

    private fun stopListening() {
        val target = listeningNodeId ?: return
        val api = messageApi ?: return
        listeningNodeId = null
        try {
            api.removeListener(target)
        } catch (t: Throwable) {
            Log.w(TAG, "removeListener 抛出: ${t.javaClass.simpleName}")
        }
    }

    /** 发送任意 UTF-8 载荷。没有节点时直接返回 false，不伪造成功。 */
    fun sendRaw(payload: ByteArray): Boolean {
        if (!sdkAvailable) return false
        val target = nodeId ?: return false
        val api = messageApi ?: return false
        return try {
            api.sendMessage(target, payload)
                .addOnFailureListener { error ->
                    Log.w(TAG, "sendMessage 失败: ${error.message}")
                }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendMessage 抛出: ${t.javaClass.simpleName}")
            false
        }
    }
}