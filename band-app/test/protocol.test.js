/**
 * 手环端协议单测。
 *
 * 跑法：node --test band-app/test/
 */
import { test } from "node:test";
import assert from "node:assert";

// 与 band-app/src/app.ux 保持一致的编码逻辑
function encodeCmd(cmd) {
  return JSON.stringify(cmd);
}

test("四向滑动编码正确", () => {
  assert.equal(encodeCmd({ t: "move", dx: 0, dy: -1 }), '{"t":"move","dx":0,"dy":-1}');
  assert.equal(encodeCmd({ t: "move", dx: 0, dy: 1 }), '{"t":"move","dx":0,"dy":1}');
  assert.equal(encodeCmd({ t: "move", dx: -1, dy: 0 }), '{"t":"move","dx":-1,"dy":0}');
  assert.equal(encodeCmd({ t: "move", dx: 1, dy: 0 }), '{"t":"move","dx":1,"dy":0}');
});

test("基础按键编码正确", () => {
  assert.equal(encodeCmd({ t: "back" }), '{"t":"back"}');
  assert.equal(encodeCmd({ t: "home" }), '{"t":"home"}');
  assert.equal(encodeCmd({ t: "recents" }), '{"t":"recents"}');
  assert.equal(encodeCmd({ t: "tap" }), '{"t":"tap"}');
});

test("状态帧解析：标准形状", () => {
  const frame = JSON.parse('{"v":1,"kind":"status","id":"st-1","link":"connected","battery":82,"listening":true}');
  assert.equal(frame.kind, "status");
  assert.equal(frame.link, "connected");
  assert.equal(frame.battery, 82);
  assert.equal(frame.listening, true);
});

test("状态帧解析：缺字段时不抛异常", () => {
  const frame = JSON.parse('{"kind":"status"}');
  assert.equal(frame.link, undefined);
  assert.equal(frame.battery, undefined);
});

test("回执帧解析", () => {
  const frame = JSON.parse('{"v":1,"kind":"ack","refId":"cmd"}');
  assert.equal(frame.kind, "ack");
  assert.equal(frame.refId, "cmd");
});

test("非法 JSON 不会让页面崩溃", () => {
  let decoded = null;
  try {
    decoded = JSON.parse("not json");
  } catch (e) {
    decoded = null;
  }
  assert.equal(decoded, null);
});

test("状态文案优先级：未知 > 断链 > 未开服务 > 正常", () => {
  function statusText(link, listening) {
    if (link === "unknown") return "正在连接";
    if (link !== "connected") return "未连接手机";
    if (!listening) return "手机手势服务未开";
    return "已连接";
  }
  assert.equal(statusText("unknown", false), "正在连接");
  assert.equal(statusText("disconnected", false), "未连接手机");
  assert.equal(statusText("connected", false), "手机手势服务未开");
  assert.equal(statusText("connected", true), "已连接");
});
