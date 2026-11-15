"use strict";

/**
 * 手环端协议单测：直接引用 src/common/protocol.js，不测副本。
 *
 * 跑法：node --test band-app/test/protocol.test.js
 * 注意：Vela 运行时是 CommonJS，测试也按 CommonJS 加载。
 */
const { test } = require("node:test");
const assert = require("node:assert");
const { CMD, encode, parseFrame, statusText } = require("../src/common/protocol.js");

test("四向滑动编码正确", () => {
  assert.equal(encode(CMD.move(0, -1)), '{"t":"move","dx":0,"dy":-1}');
  assert.equal(encode(CMD.move(0, 1)), '{"t":"move","dx":0,"dy":1}');
  assert.equal(encode(CMD.move(-1, 0)), '{"t":"move","dx":-1,"dy":0}');
  assert.equal(encode(CMD.move(1, 0)), '{"t":"move","dx":1,"dy":0}');
});

test("位移保留两位小数", () => {
  const c = CMD.move(0.333333, -0.666666);
  assert.equal(c.dx, 0.33);
  assert.equal(c.dy, -0.67);
});

test("基础按键编码正确", () => {
  assert.equal(encode(CMD.back()), '{"t":"back"}');
  assert.equal(encode(CMD.home()), '{"t":"home"}');
  assert.equal(encode(CMD.recents()), '{"t":"recents"}');
  assert.equal(encode(CMD.tap()), '{"t":"tap"}');
});

test("音量钳制在 ±1", () => {
  assert.equal(CMD.volume(5).delta, 1);
  assert.equal(CMD.volume(-9).delta, -1);
  assert.equal(CMD.volume(0).delta, 0);
});

test("status 帧正常解析", () => {
  const f = parseFrame('{"v":1,"kind":"status","id":"st-1","link":"connected","battery":82,"listening":true}');
  assert.equal(f.kind, "status");
  assert.equal(f.id, "st-1");
  assert.equal(f.link, "connected");
  assert.equal(f.battery, 82);
  assert.equal(f.listening, true);
});

test("ack 帧正常解析", () => {
  const f = parseFrame('{"v":1,"kind":"ack","refId":"cmd"}');
  assert.equal(f.kind, "ack");
  assert.equal(f.refId, "cmd");
});

test("缺字段给安全默认值，不猜", () => {
  const f = parseFrame('{"kind":"status"}');
  assert.equal(f.link, "unknown");
  assert.equal(f.battery, -1);
  assert.equal(f.listening, false);
  assert.equal(f.id, "");
});

test("非法输入返回 null，不抛异常", () => {
  assert.equal(parseFrame("not json"), null);
  assert.equal(parseFrame(""), null);
  assert.equal(parseFrame("{broken"), null);
  assert.equal(parseFrame("null"), null);
});

test("未知 kind 返回 null", () => {
  assert.equal(parseFrame('{"kind":"unknown"}'), null);
  assert.equal(parseFrame('{"foo":1}'), null);
});

test("状态文案按优先级：断链 > 未开服务 > 正常", () => {
  assert.equal(statusText(false, false), "未连接手机");
  assert.equal(statusText(false, true), "未连接手机");
  assert.equal(statusText(true, false), "手机手势服务未开");
  assert.equal(statusText(true, true), "已连接");
});

test("round2 处理边界值", () => {
  const { round2 } = require("../src/common/protocol.js");
  assert.equal(round2(0), 0);
  assert.equal(round2(-0.001), -0);
  assert.equal(round2(1.005), 1);
  assert.equal(round2(-1), -1);
});
