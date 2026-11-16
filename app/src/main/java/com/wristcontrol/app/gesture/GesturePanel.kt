package com.wristcontrol.app.gesture

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 手势管理界面：学习新手势、查看已有手势、删除。
 *
 * 刻意做成最简单的列表：这个 App 的价值在手环侧，手机端是配置页。
 */
class GesturePanel(private val activity: Activity) {

    private val store = GestureStore(activity)
    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 32, 48, 48)
    }
    private val listBox = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun build(): ScrollView {
        root.addView(TextView(activity).apply {
            text = "手势控制"
            textSize = 20f
        })

        root.addView(TextView(activity).apply {
            text = "打开手环上的「体感」开关，倾斜手腕画出方向序列。\n" +
                "八方向两两组合，最多 64 种手势。\n\n" +
                "手环只有加速度计、没有陀螺仪，所以识别的是「倾斜方向」\n" +
                "而不是空中轨迹——倾角是绝对测量，不会漂移。"
            textSize = 14f
            setPadding(0, 16, 0, 24)
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

    /** 开始学习：提示用户去手环上做动作，然后选择识别结果。 */
    private fun startLearn() {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val first = arrayOfNulls<String>(1)

        AlertDialog.Builder(activity)
            .setTitle("第一步：选第一个方向")
            .setItems(directions) { _, which ->
                first[0] = directions[which]
                chooseSecond(first[0]!!)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseSecond(firstDir: String) {
        val directions = arrayOf("（只要一个方向）", "N", "NE", "E", "SE", "S", "SW", "W", "NW")
        AlertDialog.Builder(activity)
            .setTitle("第二步：选第二个方向（可跳过）")
            .setItems(directions) { _, which ->
                val path = if (which == 0) listOf(firstDir) else listOf(firstDir, directions[which])
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
                Toast.makeText(activity, "已保存：$path → ${actions[which]}", Toast.LENGTH_SHORT).show()
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
