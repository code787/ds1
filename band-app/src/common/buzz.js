/**
 * 震动反馈。
 *
 * **必须在 manifest.json 的 features 里声明 system.vibrator**，
 * 否则 aiot-toolkit 打包时会报 "Copy resource files error: missing feature"
 * ——那个报错文案极具误导性，实际是 feature 没声明。
 *
 * 分级设计（盲操作时唯一的反馈通道）：
 * - tick：轻震一下，指令已发出
 * - ok：短震，手势识别成功
 * - err：长震，出错
 * - alarm：长震，找手机之类需要引起注意的
 */

var vibrator = require("@system.vibrator");

// Vela 的 vibrate 只认 mode: "short" / "long"，
// count/delay 不是标准参数，传了也不生效——分级只能靠长短。
function play(mode) {
  try {
    vibrator.vibrate({ mode: mode });
  } catch (e) {
    // 手环不支持震动时静默——震动只是反馈，不是功能
  }
}

module.exports = {
  /** 轻震一下：指令已发出。 */
  tick: function () { play("short"); },

  /** 短震：成功（固件不支持连震，一下短震代替两下）。 */
  ok: function () { play("short"); },

  /** 长震：出错。 */
  err: function () { play("long"); },

  /** 长震：需要注意。 */
  alarm: function () { play("long"); },
};
