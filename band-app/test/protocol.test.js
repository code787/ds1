/**
 * 手环端协议单测——直接测 src/common/protocol.js 本体。
 *
 * 之前这里手抄了一份编码/解析逻辑，测的是抄本不是真代码，
 * 真代码里的 bug（对象帧变 "[object Object]"、tilt 键读不到）一个都测不出来。
 *
 * 跑法：npm test（node --test test/）
 */
"use strict";
const { test } = require("node:test");
const assert = require("node:assert");
const { CMD, encode, parseFrame, statusText, round2 } = require("../src/common/protocol.js");

test("四向滑动编码正确", () => {
  assert.equal(encode(CMD.move(0, -1)), '{"t":"move","dx":0,"dy":-1}');
  assert.equal(encode(CMD.move(0, 1)), '{"t":"move","dx":0,"dy":1}');
  assert.equal(encode(CMD.move(-1, 0)), '{"t":"move","dx":-1,"dy":0}');
  assert.equal(encode(CMD.move(1, 0)), '{"t":"move","dx":1,"dy":0}');
});

test("基础按键编码正确", () => {
  assert.equal(encode(CMD.back()), '{"t":"back"}');
  assert.equal(encode(CMD.home()), '{"t":"home"}');
  assert.equal(encode(CMD.recents()), '{"t":"recents"}');
  assert.equal(encode(CMD.tap()), '{"t":"tap"}');
  assert.equal(encode(CMD.screenOff()), '{"t":"screenoff"}');
  assert.equal(encode(CMD.volume(3)), '{"t":"vol","delta":1}');
  assert.equal(encode(CMD.volume(-2)), '{"t":"vol","delta":-1}');
  assert.equal(encode(CMD.ack("cmd")), '{"t":"ack","refId":"cmd"}');
});

test("round2 保留两位小数", () => {
  assert.equal(round2(0.12345), 0.12);
  assert.equal(round2(-1.005), -1);
});

test("状态帧解析：标准字符串帧（手机发的键是 tilt）", () => {
  const f = parseFrame('{"v":1,"kind":"status","id":"st-1","link":"connected","battery":82,"listening":true,"tilt":true}');
  assert.equal(f.kind, "status");
  assert.equal(f.link, "connected");
  assert.equal(f.battery, 82);
  assert.equal(f.listening, true);
  assert.equal(f.tiltOn, true);
});

test("状态帧解析：SDK 直接给对象也能解析", () => {
  const f = parseFrame({ kind: "status", link: "connected", battery: 50, listening: false, tilt: false });
  assert.ok(f);
  assert.equal(f.link, "connected");
  assert.equal(f.battery, 50);
  assert.equal(f.tiltOn, false);
});

test("状态帧解析：缺字段不抛异常，给默认值", () => {
  const f = parseFrame('{"kind":"status"}');
  assert.ok(f);
  assert.equal(f.link, "unknown");
  assert.equal(f.battery, -1);
  assert.equal(f.listening, false);
  assert.equal(f.tiltOn, false);
});

test("回执帧解析", () => {
  const f = parseFrame('{"v":1,"kind":"ack","refId":"cmd"}');
  assert.equal(f.kind, "ack");
  assert.equal(f.refId, "cmd");
});

test("非法输入一律返回 null，不崩溃", () => {
  assert.equal(parseFrame("not json"), null);
  assert.equal(parseFrame(null), null);
  assert.equal(parseFrame(123), null);
  assert.equal(parseFrame(""), null);
  assert.equal(parseFrame('{"kind":"unknown"}'), null);
});

test("状态文案优先级：未知 > 断链 > 未开服务 > 正常", () => {
  assert.equal(statusText("unknown", false), "正在连接");
  assert.equal(statusText("disconnected", false), "未连接手机");
  assert.equal(statusText("connected", false), "手机手势服务未开");
  assert.equal(statusText("connected", true), "已连接");
});
