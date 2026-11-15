package com.wristcontrol.app.transport

import android.content.Context
import android.util.Log
import com.wristcontrol.app.protocol.Command
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node

/**
 * 小米官方 interconnect SDK 1.4 的封装。
 *
 * 只走 SDK 的「自定义消息」通道（MessageApi），不使用通知桥。
 * 调用约束来自官方示例与导航环的实测：
 * - 没有 nodeId 时绝不能调用 SDK（传空 id 是伪造结果）；
 * - sendMessage 的 Task 成功只代表本地通道收下了，不代表手环收到；
 * - 失败回调给的是 Exception，不是 Status，不要调 getCode()。
 */
class XiaomiWearableTransport(private val context: Context) {

    private companion object {
        const val TAG = "WristTransport"
    }

    private val nodeApi = Wearable.getNodeApi(context)
    private val authApi = Wearable.getAuthApi(context)
    private val messageApi = Wearable.getMessageApi(context)

    @Volatile private var nodeId: String? = null
    @Volatile private var listeningNodeId: String? = null

    private var onCommand: ((Command) -> Unit)? = null
    private var onStatus: (() -> Unit)? = null

    private val listener = OnMessageReceivedListener { _, payload ->
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
        handler(command)
    }

    fun hasNode(): Boolean = !nodeId.isNullOrEmpty()

    /** 发现手环节点并注册接收监听。 */
    fun start(onCommand: (Command) -> Unit, onStatus: () -> Unit) {
        this.onCommand = onCommand
        this.onStatus = onStatus
        refreshNode { resolved ->
            if (resolved != null) {
                requestPermission { granted ->
                    if (granted) {
                        startListening(resolved)
                        onStatus()
                    } else {
                        Log.w(TAG, "未获得 DEVICE_MANAGER 权限，无法接收手环消息")
                    }
                }
            }
        }
    }

    fun stop() {
        stopListening()
        onCommand = null
        onStatus = null
    }

    private fun refreshNode(onResult: (String?) -> Unit) {
        try {
            nodeApi.getConnectedNodes()
                .addOnSuccessListener { nodes: List<Node>? ->
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
     * 自定义消息通道需要 DEVICE_MANAGER 权限，未授予时先请求。
     *
     * 注意 SDK 的两个 Task 返回类型不同：
     * - checkPermission 返回 Boolean（是否已授予）
     * - requestPermission 返回 Array<Permission>（本次被授予的权限列表）
     * 把后者当 Boolean 用会编译失败：Operator '==' cannot be applied to Array<Permission> and Boolean。
     */
    private fun requestPermission(onResult: (Boolean) -> Unit) {
        val target = nodeId ?: return onResult(false)
        try {
            authApi.checkPermission(target, Permission.DEVICE_MANAGER)
                .addOnSuccessListener { granted ->
                    if (granted == true) {
                        onResult(true)
                    } else {
                        authApi.requestPermission(target, Permission.DEVICE_MANAGER)
                            .addOnSuccessListener { grantedList ->
                                // 请求成功不代表一定授予；以返回列表里是否含该权限为准。
                                val grantedNow = grantedList?.any { it == Permission.DEVICE_MANAGER } == true
                                if (!grantedNow) {
                                    Log.w(TAG, "requestPermission 返回的列表里没有 DEVICE_MANAGER")
                                }
                                onResult(grantedNow)
                            }
                            .addOnFailureListener { error ->
                                Log.w(TAG, "requestPermission 失败: ${error.message}")
                                onResult(false)
                            }
                    }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "checkPermission 失败: ${error.message}")
                    onResult(false)
                }
        } catch (t: Throwable) {
            Log.w(TAG, "权限检查抛出: ${t.javaClass.simpleName}")
            onResult(false)
        }
    }

    private fun startListening(target: String) {
        if (listeningNodeId == target) return
        val previous = listeningNodeId
        listeningNodeId = target
        try {
            if (previous != null && previous != target) {
                messageApi.removeListener(previous)
            }
            messageApi.addListener(target, listener)
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
        listeningNodeId = null
        try {
            messageApi.removeListener(target)
        } catch (t: Throwable) {
            Log.w(TAG, "removeListener 抛出: ${t.javaClass.simpleName}")
        }
    }

    /** 发送任意 UTF-8 载荷。没有节点时直接返回 false，不伪造成功。 */
    fun sendRaw(payload: ByteArray): Boolean {
        val target = nodeId ?: return false
        return try {
            messageApi.sendMessage(target, payload)
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