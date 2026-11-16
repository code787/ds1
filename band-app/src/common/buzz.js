/**
 * 震动反馈。
 *
 * **必须在 manifest.json 的 features 里声明 system.vibrator**，
 * 否则 aiot-toolkit 打包时会报 "Copy resource files error: missing feature"
 * ——那个报错文案极具误导性，实际是 feature 没声明。
 *
 * 分级设计（盲操作时唯一的反馈通道）：
 * - tick：轻震一下，指令已发出
 * - ok：两下短震，手势识别成功
 * - err：长震，出错
 * - alarm：连续震，找手机之类需要引起注意的
 */

var vibrator = require("@system.vibrator");

function play(pattern) {
  try {
    vibrator.vibrate({
      mode: "short",
      count: pattern.count,
      delay: pattern.delay,
    });
  } catch (e) {
    // 手环不支持震动时静默——震动只是反馈，不是功能
  }
}

module.exports = {
  /** 轻震一下。 */
  tick: function () { play({ count: 1, delay: 0 }); },

  /** 两下短震：成功。 */
  ok: function () { play({ count: 2, delay: 80 }); },

  /** 长震：出错。 */
  err: function () { play({ count: 1, delay: 0 }); },

  /** 连续震：需要注意。 */
  alarm: function () { play({ count: 4, delay: 150 }); },
};
