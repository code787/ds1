package com.wristcontrol.app.gesture

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * 手势控制面板：阈值调节 + 实时诊断 + 手势管理。
 *
 * 三块内容：
 * 1. 实时诊断：显示手环当前识别到的方向（左上/右上…）
 * 2. 阈值调节：死区、保持时长、容错，改完立即下发
 * 3. 手势列表：学习、查看、删除
 */
class GesturePanel(private val activity: Activity) {

    private val store = GestureStore(activity)
    private val cfgStore = GestureConfigStore(activity)
    private var cfg = cfgStore.load()
    private var rotationValue: TextView? = null

    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 32, 48, 48)
    }
    private val listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val liveBox = TextView(activity).apply {
        textSize = 22f
        setPadding(0, 16, 0, 16)
    }
    private val liveSub = TextView(activity).apply {
        textSize = 14f
        setPadding(0, 0, 0, 16)
    }

    /** 传感器原始数据（等宽字体，数值对齐才好读）。 */
    private val sensorBox = TextView(activity).apply {
        textSize = 13f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, 8, 0, 16)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            refreshLive()
            handler.postDelayed(this, 150)
        }
    }

    fun build(): ScrollView {
        root.addView(TextView(activity).apply {
            text = "体感手势"
            textSize = 20f
        })

        // ---- 实时诊断 ----
        root.addView(TextView(activity).apply {
            text = "实时诊断"
            textSize = 16f
            setPadding(0, 24, 0, 4)
        })
        root.addView(TextView(activity).apply {
            text = "打开手环上的「体感」开关，倾斜手腕即可看到方向。"
            textSize = 13f
        })
        root.addView(liveBox)
        root.addView(liveSub)

        // ---- 传感器数据 ----
        root.addView(TextView(activity).apply {
            text = "传感器数据"
            textSize = 16f
            setPadding(0, 8, 0, 0)
        })
        root.addView(TextView(activity).apply {
            text = "手环只有加速度计，没有陀螺仪——这里没有角速度。"
            textSize = 12f
        })
        root.addView(sensorBox)

        // ---- 阈值调节 ----
        root.addView(TextView(activity).apply {
            text = "识别参数"
            textSize = 16f
            setPadding(0, 16, 0, 8)
        })

        addSlider("死区角度", "倾斜小于这个角度视为没动。越小越灵敏", 4, 40, cfg.deadZone) { v ->
            cfg = cfg.copy(deadZone = v)
            applyCfg()
        }
        addSlider("方向保持", "同一方向需持续多久才确认。越小越跟手", 40, 500, cfg.holdMs) { v ->
            cfg = cfg.copy(holdMs = v)
            applyCfg()
        }
        addSlider("静止判定", "停多久算手势结束。越大越不容易被切断", 200, 2000, cfg.idleEndMs) { v ->
            cfg = cfg.copy(idleEndMs = v)
            applyCfg()
        }
        addSlider("匹配容错", "允许差几个方向。0=必须完全一致", 0, 2, cfg.tolerance) { v ->
            cfg = cfg.copy(tolerance = v)
            applyCfg()
        }

        // ---- 手势列表 ----
        // ---- 坐标系旋转 ----
        root.addView(TextView(activity).apply {
            text = "坐标系旋转偏移"
            textSize = 16f
            setPadding(0, 24, 0, 4)
        })
        root.addView(TextView(activity).apply {
            text = "横向佩戴（屏幕长边水平）默认 90°，竖着戴改 0°。\n如果上下的动作识别成左右，就是差 90 度。"
            textSize = 13f
        })
        rotationValue = TextView(activity).apply {
            text = "当前：" + cfgStore.load().rotationOffset + "°"
            textSize = 15f
            setPadding(0, 8, 0, 0)
        }
        root.addView(rotationValue)
        root.addView(android.widget.SeekBar(activity).apply {
            max = 360
            progress = (cfgStore.load().rotationOffset + 180)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val value = p - 180
                    rotationValue?.text = "当前：" + value + "°"
                    saveCfg(cfgStore.load().copy(rotationOffset = value))
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })

        root.addView(Button(activity).apply {
            text = "＋ 学习新手势"
            setOnClickListener { startLearn() }
        })

        root.addView(TextView(activity).apply {
            text = "已保存的手势"
            textSize = 16f
            setPadding(0, 32, 0, 12)
        })
        root.addView(listBox)

        refresh()
        return ScrollView(activity).apply { addView(root) }
    }

    /** 页面显示时开始轮询实时方向。 */
    fun onResume() {
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    fun onPause() {
        handler.removeCallbacks(poller)
    }

    private fun refreshLive() {
        val svc = com.wristcontrol.app.transport.ServiceRef.current
        if (svc == null) {
            if (liveBox.text != "桥接未启动") liveBox.text = "桥接未启动"
            if (liveSub.text != "先去主页点「启动桥接」") liveSub.text = "先去主页点「启动桥接」"
            return
        }
        val dir = svc.lastLiveDir
        val path = svc.lastLivePath
        val label = dirLabel(dir)
        if (liveBox.text != label) liveBox.text = label
        val sub = if (path.isNotEmpty()) "本次序列: $path" else "还没识别到方向"
        if (liveSub.text != sub) liveSub.text = sub

        // 传感器原始数据
        val t = svc.lastTilt
        val text = if (t == null) {
            "等待手环上报…\n（需打开手环上的体感开关）"
        } else {
            val fmt = java.util.Locale.US
            "ax  %8.2f\n".format(fmt, t.ax) +
                "ay  %8.2f\n".format(fmt, t.ay) +
                "az  %8.2f\n".format(fmt, t.az) +
                "角度 %7.1f°\n".format(fmt, t.angle) +
                "方向 %s".format(t.dir)
        }
        if (sensorBox.text != text) sensorBox.text = text
    }

    private fun dirLabel(code: String): String = when (code) {
        "N" -> "⬆ 上"
        "E" -> "➡ 右"
        "S" -> "⬇ 下"
        "W" -> "⬅ 左"
        else -> "— 未倾斜"
    }

    private fun addSlider(title: String, hint: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
        val valueLabel = TextView(activity).apply {
            text = "$title: $value"
            textSize = 15f
        }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
            addView(valueLabel)
            addView(TextView(activity).apply {
                text = hint
                textSize = 12f
            })
            addView(SeekBar(activity).apply {
                this.max = max - min
                progress = (value - min).coerceIn(0, max - min)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val v = min + p
                        valueLabel.text = "$title: $v"
                        onChange(v)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        })
    }

    private fun applyCfg() {
        cfgStore.save(cfg)
        com.wristcontrol.app.transport.ServiceRef.current?.pushConfig()
    }

    /** 保存配置并立即下发手环。 */
    private fun saveCfg(c: GestureConfig) {
        cfgStore.save(c)
        try {
            com.wristcontrol.app.transport.ServiceRef.current?.pushConfig()
        } catch (t: Throwable) {
            // 服务没起时忽略，下次启动会重新下发
        }
    }

    private fun refresh() {
        listBox.removeAllViews()
        val list = store.load()
        if (list.isEmpty()) {
            listBox.addView(TextView(activity).apply {
                text = "（还没有）"
                textSize = 14f
            })
            return
        }
        for (g in list) {
            listBox.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
                addView(TextView(activity).apply {
                    text = g.path.joinToString(" → ") + "\n" + g.name + " → " + actionLabel(g.action)
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Button(activity).apply {
                    text = "删除"
                    setOnClickListener {
                        store.remove(g.id)
                        refresh()
                        Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
                    }
                })
            })
        }
    }

    private fun startLearn() {
        val dirs = arrayOf("N 上", "NE 右上", "E 右", "SE 右下", "S 下", "SW 左下", "W 左", "NW 左上")
        val codes = arrayOf("N", "E", "S", "W")
        val first = arrayOfNulls<String>(1)

        AlertDialog.Builder(activity)
            .setTitle("第一步：第一个方向")
            .setItems(dirs) { _, which ->
                first[0] = codes[which]
                chooseSecond(first[0]!!)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseSecond(firstDir: String) {
        val dirs = arrayOf("（只要一个方向）", "N 上", "NE 右上", "E 右", "SE 右下", "S 下", "SW 左下", "W 左", "NW 左上")
        val codes = arrayOf("", "N", "E", "S", "W")
        AlertDialog.Builder(activity)
            .setTitle("第二步：第二个方向（可跳过）")
            .setItems(dirs) { _, which ->
                val path = if (which == 0) listOf(firstDir) else listOf(firstDir, codes[which])
                chooseAction(path)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseAction(path: List<String>) {
        val actions = arrayOf("返回", "主页", "多任务", "点击", "上滑", "下滑", "左滑", "右滑", "音量+", "音量-", "锁屏")
        val codes = arrayOf("back", "home", "recents", "tap", "up", "down", "left", "right", "volup", "voldown", "screenoff")
        AlertDialog.Builder(activity)
            .setTitle("第三步：绑定操作")
            .setItems(actions) { _, which ->
                val g = Gesture(
                    id = "g-" + System.currentTimeMillis(),
                    name = path.joinToString("") + " 手势",
                    path = path,
                    action = codes[which],
                )
                store.add(g)
                refresh()
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun actionLabel(code: String): String = when (code) {
        "back" -> "返回"
        "home" -> "主页"
        "recents" -> "多任务"
        "tap" -> "点击"
        "up" -> "上滑"
        "down" -> "下滑"
        "left" -> "左滑"
        "right" -> "右滑"
        "volup" -> "音量+"
        "voldown" -> "音量-"
        "screenoff" -> "锁屏"
        else -> code
    }
}