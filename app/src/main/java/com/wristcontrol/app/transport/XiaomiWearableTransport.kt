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
    private val serviceApi by lazy { runCatching { com.xiaomi.xms.wearable.Wearable.getServiceApi(context) }.getOrNull() }

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
        step("SDK 已初始化")

        // 必须先确认「小米运动健康」的宿主服务已连上，再去查节点。
        // 直接查节点在宿主未绑定时会稳定返回空列表，看起来像"没有手环"，
        // 实际是通道还没建立（导航环的通道诊断文档把这两件事分开记）。
        registerHostListener()

        refreshNode { resolved, reason ->
            if (resolved == null) {
                fail("未找到已连接的手环（${reason}）。请确认：1) 小米运动健康已登录 2) 手环已连接 3) 小米运动健康在后台运行")
                return@refreshNode
            }
            step("找到节点 ${resolved.take(8)}…")

            // 手环上有没有装我们这个快应用？
            // 没装的话后续 checkPermission 会报 signature verify failed，
            // 看起来像证书问题，实际是包不在手环上。
            checkWearAppInstalled(resolved)

            requestPermission { granted, reason ->
                if (granted) {
                    step("设备管理权限已授予")
                    startListening(resolved)
                    safeStatus()
                } else {
                    fail("未获得设备管理权限（${reason}）")
                }
            }
        }
    }

    fun stop() {
        try {
            hostListener?.let { serviceApi?.unregisterServiceConnectionListener(it) }
            hostListener = null
        } catch (t: Throwable) {
            Log.w(TAG, "注销宿主监听异常: ${t.javaClass.simpleName}")
        }
        try {
            stopListening()
        } catch (t: Throwable) {
            Log.w(TAG, "stopListening 异常: ${t.javaClass.simpleName}")
        }
        onCommand = null
        onStatus = null
        onError = null
    }

    /**
     * 检查手环上是否安装了同包名的快应用。
     *
     * SDK 提供 isWearAppInstalled(packageName) —— 这是区分
     * 「手环没装 App」和「证书不匹配」的关键：
     * 两者都可能表现为 signature verify failed。
     */
    private fun checkWearAppInstalled(target: String) {
        val api = nodeApi
        if (api == null) return
        try {
            api.isWearAppInstalled(context.packageName)
                .addOnSuccessListener { installed ->
                    DiagnoseLog.append(context, "isWearAppInstalled(${context.packageName}) = $installed")
                    step("手环快应用: ${if (installed == true) "已安装" else "未安装"}")
                }
                .addOnFailureListener { e ->
                    DiagnoseLog.append(context, "isWearAppInstalled 失败: ${e.javaClass.name} msg=${e.message}")
                    step("手环快应用检查失败: ${e.javaClass.simpleName}")
                }
        } catch (t: Throwable) {
            DiagnoseLog.append(context, "isWearAppInstalled 抛出: ${t.javaClass.name}")
        }
    }

    /** 宿主服务连接监听；只用于诊断，不参与业务判断。 */
    private var hostListener: com.xiaomi.xms.wearable.service.OnServiceConnectionListener? = null

    /**
     * 注册宿主服务连接监听，并查询 API level。
     *
     * 这两步是「找不到手环」时区分原因的关键：
     * - 宿主服务没连上 → 小米运动健康没装/没登录/被冻结
     * - 宿主连上但节点为空 → 手环没连上，或蓝牙通道被别的工具占用
     */
    private fun registerHostListener() {
        val api = serviceApi
        if (api == null) {
            step("宿主服务 API 不可用")
            return
        }
        try {
            val listener = object : com.xiaomi.xms.wearable.service.OnServiceConnectionListener {
                override fun onServiceConnected() {
                    Log.i(TAG, "宿主服务已连接")
                    step("宿主服务已连接")
                }

                override fun onServiceDisconnected() {
                    Log.w(TAG, "宿主服务已断开")
                    step("宿主服务已断开")
                }
            }
            hostListener = listener
            api.registerServiceConnectionListener(listener)

            api.getServiceApiLevel()
                .addOnSuccessListener { level -> step("宿主服务 API level=${level ?: "未知"}") }
                .addOnFailureListener { e ->
                    step("宿主服务 API 查询失败: ${e.javaClass.simpleName}")
                }
        } catch (t: Throwable) {
            step("注册宿主监听失败: ${t.javaClass.simpleName}")
        }
    }

    /** 分步诊断：每一步都同时推到界面和日志文件。 */
    private fun step(message: String) {
        Log.i(TAG, "步骤: $message")
        DiagnoseLog.append(context, "步骤: $message")
        try {
            onError?.invoke("步骤: $message")
        } catch (t: Throwable) {
            // 诊断失败不能影响主流程
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        DiagnoseLog.append(context, "失败: $message")
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

    private fun refreshNode(onResult: (String?, String) -> Unit) {
        val api = nodeApi
        if (api == null) {
            onResult(null, "SDK 节点 API 不可用")
            return
        }
        try {
            api.getConnectedNodes()
                .addOnSuccessListener { nodes: List<com.xiaomi.xms.wearable.node.Node>? ->
                    val first = nodes?.firstOrNull()
                    nodeId = first?.id
                    val detail = "节点数=${nodes?.size ?: 0} 名称=${first?.name ?: "(无)"} id=${nodeId ?: "(空)"}"
                    DiagnoseLog.append(context, "getConnectedNodes -> $detail")
                    nodes?.forEachIndexed { i, n ->
                        DiagnoseLog.append(context, "  节点[$i] name=${n.name} id=${n.id}")
                    }
                    onResult(nodeId, if (nodes.isNullOrEmpty()) "节点列表为空（${nodes?.size ?: 0} 个）" else "ok")
                }
                .addOnFailureListener { error ->
                    nodeId = null
                    DiagnoseLog.append(context, "getConnectedNodes 失败: ${error.javaClass.name} msg=${error.message}")
                    onResult(null, "getConnectedNodes 失败: ${error.javaClass.simpleName}")
                }
        } catch (t: Throwable) {
            nodeId = null
            Log.w(TAG, "getConnectedNodes 抛出: ${t.javaClass.simpleName}")
            onResult(null, "getConnectedNodes 抛出: ${t.javaClass.simpleName}")
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
    private fun requestPermission(onResult: (Boolean, String) -> Unit) {
        val target = nodeId
        if (target == null) {
            onResult(false, "没有节点 id，跳过权限请求")
            return
        }
        val api = authApi
        if (api == null) {
            onResult(false, "SDK 权限 API 不可用")
            return
        }

        // SDK 的 Task 回调不保证在主线程，而 requestPermission 会弹系统授权对话框。
        // 统一切回主线程，避免"对话框没弹出来却报了未授予"。
        val main = android.os.Handler(android.os.Looper.getMainLooper())

        try {
            api.checkPermission(target, com.xiaomi.xms.wearable.auth.Permission.DEVICE_MANAGER)
                .addOnSuccessListener { already ->
                    if (already == true) {
                        Log.i(TAG, "设备管理权限：已授予（无需请求）")
                        main.post { onResult(true, "已授予") }
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
                                        main.post { onResult(ok, if (ok) "请求后已授予" else "请求返回空列表（用户拒绝或未弹框）") }
                                    }
                                    .addOnFailureListener { error ->
                                        Log.w(TAG, "requestPermission 失败: ${error.javaClass.simpleName} ${error.message}")
                                        main.post { onResult(false, "requestPermission 失败: ${error.javaClass.simpleName}") }
                                    }
                            } catch (t: Throwable) {
                                Log.w(TAG, "requestPermission 抛出: ${t.javaClass.simpleName}")
                                main.post { onResult(false, "requestPermission 抛出: ${t.javaClass.simpleName}") }
                            }
                        }
                    }
                }
                .addOnFailureListener { error ->
                    DiagnoseLog.append(
                        context,
                        "checkPermission 失败: ${error.javaClass.name} msg=${error.message}",
                    )
                    main.post { onResult(false, "checkPermission 失败: ${error.javaClass.simpleName}") }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "权限检查抛出: ${t.javaClass.simpleName}")
            onResult(false, "checkPermission 抛出: ${t.javaClass.simpleName}")
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