"use strict";

/**
 * 手环 → 手机的指令编码（CommonJS）。
 *
 * Vela 快应用运行时不支持 ESM，必须用 require / module.exports。
 * 每个指令都是一个小 JSON，字段短，减少蓝牙帧体积。
 * 不在手环端做任何"猜测"：方向只有明确的四向，没有中间态。
 */

const CMD = {
  move: (dx, dy) => ({ t: "move", dx: round2(dx), dy: round2(dy) }),
  tap: () => ({ t: "tap" }),
  back: () => ({ t: "back" }),
  home: () => ({ t: "home" }),
  recents: () => ({ t: "recents" }),
  volume: (delta) => ({ t: "vol", delta: delta > 0 ? 1 : delta < 0 ? -1 : 0 }),
  screenOff: () => ({ t: "screenoff" }),
  ping: () => ({ t: "ping" }),
  ack: (refId) => ({ t: "ack", refId }),
};

function round2(v) {
  return Math.round(v * 100) / 100;
}

function encode(cmd) {
  return JSON.stringify(cmd);
}

/** 解析手机发来的帧。无法识别返回 null，绝不猜。 */
function parseFrame(payload) {
  try {
    // SDK 可能直接给对象；String(对象) 会变成 "[object Object]"，必须区分
    const o = typeof payload === "string" ? JSON.parse(payload) : payload;
    if (!o || typeof o !== "object") return null;
    if (o.kind === "ack") {
      return { kind: "ack", refId: typeof o.refId === "string" ? o.refId : "" };
    }
    if (o.kind === "status") {
      return {
        kind: "status",
        id: typeof o.id === "string" ? o.id : "",
        link: typeof o.link === "string" ? o.link : "unknown",
        battery: typeof o.battery === "number" ? o.battery : -1,
        listening: o.listening === true,
        // 手机端 StatusCodec 发的键是 "tilt"（兼容旧写法 tiltOn）
        tiltOn: o.tilt === true || o.tiltOn === true,
      };
    }
    return null;
  } catch (e) {
    return null;
  }
}

/** 链路状态 → 界面文案。优先级：未知 > 断链 > 未开手势服务 > 正常。 */
function statusText(link, listening) {
  if (link === "unknown") return "正在连接";
  if (link !== "connected") return "未连接手机";
  if (!listening) return "手机手势服务未开";
  return "已连接";
}

module.exports = { CMD, encode, parseFrame, statusText, round2 };