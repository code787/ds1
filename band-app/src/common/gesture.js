/**
 * 手环端手势识别。
 *
 * 只用加速度计（system.sensor.subscribeAccelerometer）：
 * 小米手环 9 Pro 支持加速度计，**没有陀螺仪**，所以算不出角速度。
 * 用绝对倾角映射八方向——倾角是绝对测量不漂移，位置积分会漂移。
 */

/** 死区角度（度）。小于这个值视为没倾斜。 */
var DEAD_ZONE = 12;

/** 采样间隔：game 档约 20ms 一次。 */
var INTERVAL = "game";

/** 同一方向持续这么久才确认（毫秒），防止抖动产生 W,W,W。 */
var HOLD_MS = 120;

/** 一次手势最多记录几个方向。 */
var MAX_STEPS = 2;

/** 手势结束判定：静止这么久就算画完（毫秒）。 */
var IDLE_END_MS = 600;

/**
 * 把倾角映射到八方向。
 * x 向右为正，y 向上为正；死区内返回 null。
 */
function toDir(x, y) {
  var mag = Math.sqrt(x * x + y * y);
  if (mag < DEAD_ZONE) return null;
  var angle = Math.atan2(y, x) * 180 / Math.PI;
  var n = ((angle % 360) + 360) % 360;
  if (n < 22.5 || n >= 337.5) return "E";
  if (n < 67.5) return "NE";
  if (n < 112.5) return "N";
  if (n < 157.5) return "NW";
  if (n < 202.5) return "W";
  if (n < 247.5) return "SW";
  if (n < 292.5) return "S";
  return "SE";
}

/**
 * 手势录制器。
 *
 * 用法：
 *   var r = createRecorder();
 *   r.start();            // 开始录
 *   r.feed(ax, ay);       // 每次采样喂进来
 *   var path = r.finish(); // 结束，拿方向序列
 */
function createRecorder() {
  var steps = [];
  var current = null;
  var currentSince = 0;
  var lastMoveAt = 0;

  return {
    start: function () {
      steps = [];
      current = null;
      currentSince = 0;
      lastMoveAt = Date.now();
    },

    feed: function (x, y) {
      var now = Date.now();
      var dir = toDir(x, y);

      if (dir === null) {
        // 回到死区：方向断开
        current = null;
        currentSince = 0;
        return;
      }

      if (dir !== current) {
        current = dir;
        currentSince = now;
        return;
      }

      // 同方向持续够久才算数
      if (now - currentSince >= HOLD_MS) {
        var last = steps.length > 0 ? steps[steps.length - 1] : null;
        if (last !== dir) {
          if (steps.length < MAX_STEPS) {
            steps.push(dir);
            lastMoveAt = now;
          }
        }
      }
    },

    /** 是否已经静止足够久（可以结束录制）。 */
    isIdle: function () {
      return steps.length > 0 && Date.now() - lastMoveAt >= IDLE_END_MS;
    },

    /** 当前已录到的方向，用于界面实时显示。 */
    path: function () { return steps.slice(); },

    /** 当前指向的方向（未达确认时长时也显示，用于反馈）。 */
    hint: function () { return current; },

    finish: function () { return steps.slice(); },
  };
}

/**
 * 手势模板匹配（编辑距离）。
 *
 * 与手机端 GestureMatcher 保持一致：宁可不动，也不猜。
 */
function distance(a, b) {
  if (a.length === 0) return b.length;
  if (b.length === 0) return a.length;
  var prev = [];
  for (var j = 0; j <= b.length; j++) prev[j] = j;
  for (var i = 1; i <= a.length; i++) {
    var cur = [i];
    for (var k = 1; k <= b.length; k++) {
      var cost = a[i - 1] === b[k - 1] ? 0 : 1;
      cur[k] = Math.min(cur[k - 1] + 1, prev[k] + 1, prev[k - 1] + cost);
    }
    prev = cur;
  }
  return prev[b.length];
}

/** 在模板里找最接近的一条；没有足够接近的返回 null。 */
function bestMatch(actual, templates, maxDistance) {
  var limit = maxDistance === undefined ? 1 : maxDistance;
  if (actual.length === 0 || templates.length === 0) return null;
  var best = null;
  var bestDist = 999;
  for (var i = 0; i < templates.length; i++) {
    var d = distance(actual, templates[i].path);
    if (d < bestDist) {
      bestDist = d;
      best = templates[i];
    }
  }
  return bestDist <= limit ? best : null;
}

module.exports = {
  DEAD_ZONE: DEAD_ZONE,
  INTERVAL: INTERVAL,
  HOLD_MS: HOLD_MS,
  IDLE_END_MS: IDLE_END_MS,
  toDir: toDir,
  createRecorder: createRecorder,
  distance: distance,
  bestMatch: bestMatch,
};
