package com.wristcontrol.app.transport

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把诊断过程写成文本文件，放在 App 的外部私有目录：
 *
 *   /sdcard/Android/data/com.wristcontrol.app/files/diagnose.log
 *
 * 为什么需要它：手机上没有 adb，logcat 读不到；
 * 界面上的通知只能显示一行，看不清完整链路。
 * 落盘之后用户可以把文件发出来，定位问题不用再靠猜。
 *
 * 只记录 SDK 调用与状态，**不记录任何用户数据**。
 */
object DiagnoseLog {

    private const val TAG = "WristDiag"
    private const val FILE_NAME = "diagnose.log"
    private const val MAX_BYTES = 64 * 1024

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun append(context: Context, message: String) {
        val line = "${fmt.format(Date())}  $message"
        Log.i(TAG, message)
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            dir.mkdirs()
            val file = File(dir, FILE_NAME)
            // 简单轮转：超过上限就清空重来，避免无限增长
            if (file.exists() && file.length() > MAX_BYTES) {
                file.delete()
            }
            file.appendText(line + "\n")
        } catch (t: Throwable) {
            Log.w(TAG, "写日志失败: ${t.javaClass.simpleName}")
        }
    }

    /** 供界面显示"日志在哪"。 */
    fun path(context: Context): String {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME).absolutePath
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, FILE_NAME).delete()
        } catch (t: Throwable) {
            Log.w(TAG, "清日志失败: ${t.javaClass.simpleName}")
        }
    }
}
