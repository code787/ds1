#!/usr/bin/env node
/**
 * 提交前代码审查。
 *
 * 这套检查是被 bug 逼出来的：连续 5 次出现「改了一半」的错误——
 * 加了字段但没打通链路、加了分支但旧的先 return、加了接口但调用方没跟上。
 * 人工 review 抓不住，编译器也抓不住，只能靠工具。
 *
 * 跑法：node scripts/audit.mjs
 * 退出码非 0 表示有阻塞问题，不要提交。
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const ROOT = path.resolve(__dirname, "..");
let errors = 0;
let warnings = 0;

const fail = (msg) => { console.log("  ✗ " + msg); errors++; };
const warn = (msg) => { console.log("  ⚠ " + msg); warnings++; };
const pass = (msg) => console.log("  ✓ " + msg);

const read = (p) => fs.readFileSync(path.join(ROOT, p), "utf8");

// ---------------------------------------------------------------- 文件清单
const F = {
  proto: "app/src/main/java/com/wristcontrol/app/protocol/ControlProtocol.kt",
  svc: "app/src/main/java/com/wristcontrol/app/transport/ControlService.kt",
  cfg: "app/src/main/java/com/wristcontrol/app/gesture/GestureConfig.kt",
  panel: "app/src/main/java/com/wristcontrol/app/gesture/GesturePanel.kt",
  appux: "band-app/src/app.ux",
  page: "band-app/src/pages/index/index.ux",
  gesture: "band-app/src/common/gesture.js",
  manifest: "band-app/src/manifest.json",
};

const src = {};
for (const [k, p] of Object.entries(F)) {
  try { src[k] = read(p); } catch (e) { console.log("✗ 读不到 " + p); process.exit(1); }
}

// ---------------------------------------------------- 1. 配置字段全链路
console.log("\n[1] 配置字段全链路（GestureConfig -> 下发 -> 手环接收 -> 使用）");

const cfgBlock = src.cfg.match(/data class GestureConfig\(([\s\S]*?)\)\s*\{/);
const fields = cfgBlock
  ? [...cfgBlock[1].matchAll(/val (\w+):/g)].map((m) => m[1])
  : [];

// 字段名在协议里可能改名（showLive -> live）
const WIRE = { showLive: "live" };

const encMatch = src.proto.match(/encodeConfigSync\(([\s\S]*?)\)\s*:\s*ByteArray/);
const recvMatch = src.appux.match(/frame\.kind === "cfg"[\s\S]{0,1200}/);

if (!encMatch) fail("找不到 encodeConfigSync");
else if (!recvMatch) fail("手环端没有 cfg 分支");
else {
  for (const f of fields) {
    const wire = WIRE[f] || f;
    const sent = encMatch[1].includes(wire);
    const recv = recvMatch[0].includes("frame." + wire);
    if (!sent) fail(f + " 没下发到手环");
    else if (!recv) fail(f + " 手环没接收");
    else pass(f + " -> " + wire);
  }
}

// ------------------------------------------------ 2. 手环发送的帧 vs 解析
console.log("\n[2] 手环发出的帧 vs 手机端解析");

const sentFrames = [...src.appux.matchAll(/this\.send\(\{\s*\n?\s*t:\s*"([^"]+)"/g)].map((m) => m[1]);
const parsed = [...src.proto.matchAll(/"([a-z]+)"\s*->\s*\w+\(/g)].map((m) => m[1]);

for (const t of new Set(sentFrames)) {
  if (!parsed.includes(t)) fail('手环发 t="' + t + '" 但手机端没解析');
  else pass('t="' + t + '" 已解析');
}

// ------------------------------------------------ 3. 数据类字段 vs 解析字段
console.log("\n[3] 数据类字段 vs 解析读取的字段");

for (const cls of ["TiltLive", "GestureHit", "TiltState"]) {
  const def = src.proto.match(new RegExp("data class " + cls + "\\(([\\s\\S]*?)\\)\\s*:"));
  if (!def) continue;
  const defFields = [...def[1].matchAll(/val (\w+):/g)].map((m) => m[1]);

  // 找这个类的解析分支（从 t=xxx 到对应的类名）
  // 分支可能跨多行且含嵌套调用，用「到下一个分支或 when 结束」来截取
  const startIdx = src.proto.indexOf("-> " + cls + "(");
  if (startIdx < 0) { warn(cls + " 找不到解析分支"); continue; }
  const after = src.proto.slice(startIdx);
  const endIdx = after.search(/\n\s{16}"\w+"\s*->|\n\s{12}\}/);
  const branchText = endIdx > 0 ? after.slice(0, endIdx) : after.slice(0, 2000);
  const readFields = [...branchText.matchAll(/opt\w+\("(\w+)"/g)].map((m) => m[1]);

  const missing = defFields.filter((f) => !readFields.includes(f));
  if (missing.length) fail(cls + " 漏解析: " + missing.join(", "));
  else pass(cls + " 字段完整");
}

// ------------------------------------------------ 4. 重复分支
console.log("\n[4] 重复分支（同类 if 出现多次，旧的可能先 return）");

for (const [name, text] of [["app.ux", src.appux], ["ControlService", src.svc], ["index.ux", src.page]]) {
  const conds = [...text.matchAll(/if\s*\(\s*(command is Command\.\w+|frame\.kind === "\w+")/g)].map((m) => m[1]);
  const seen = {};
  const dup = [];
  for (const c of conds) { if (seen[c]) dup.push(c); seen[c] = 1; }
  if (dup.length) fail(name + " 重复分支: " + [...new Set(dup)].join(", "));
  else pass(name + " 无重复");
}

// ------------------------------------------------ 5. 页面调用 vs app 方法
console.log("\n[5] 页面调用 vs app.ux 暴露的方法");

const methods = new Set([...src.appux.matchAll(/^  (\w+)\s*\(/gm)].map((m) => m[1]));
const calls = new Set([...src.page.matchAll(/app\.(\w+)\(/g)].map((m) => m[1]));
for (const c of calls) {
  if (!methods.has(c)) fail("页面调用 app." + c + "() 但 app.ux 没有这个方法");
}
pass("检查 " + calls.size + " 个调用");

// ------------------------------------------------ 6. manifest features
console.log("\n[6] require 的模块 vs manifest features");

// 扫全部手环端源码（含 common/*.js），不只看 app.ux
const bandDir = path.join(ROOT, "band-app/src");
const allReq = new Set();
(function scanBand(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { scanBand(p); continue; }
    if (!/\.(js|ux)$/.test(e.name)) continue;
    const t = fs.readFileSync(p, "utf8");
    for (const m of t.matchAll(/require\("(@system\.[\w.]+)"\)/g)) allReq.add(m[1]);
  }
})(bandDir);

const mf = JSON.parse(src.manifest);
const features = new Set((mf.features || []).map((f) => f.name));

// require 写 "@system.xxx"，manifest 写 "system.xxx"，去掉 @ 再比
const norm = (s) => s.replace(/^@/, "");
const reqNorm = new Set([...allReq].map(norm));

for (const r of allReq) {
  if (!features.has(norm(r))) fail("require " + r + " 但 manifest 没声明 " + norm(r));
  else pass(r);
}
for (const f of features) {
  if (!reqNorm.has(f)) warn("manifest 声明了 " + f + " 但代码没 require");
}

// ------------------------------------------------ 7. 版本号一致
console.log("\n[7] 版本号");

const verFile = path.join(ROOT, "version.properties");
if (fs.existsSync(verFile)) {
  const ver = fs.readFileSync(verFile, "utf8");
  const vName = (ver.match(/^versionName=(.+)$/m) || [])[1];
  const vCode = (ver.match(/^versionCode=(.+)$/m) || [])[1];
  pass("version.properties: " + vCode + " / " + vName);

  const mfVer = mf.versionCode;
  if (String(mfVer) !== String(vCode).trim() && String(mfVer) !== "1") {
    warn("manifest versionCode=" + mfVer + " 与 version.properties=" + vCode + " 不一致（CI 会覆盖，本地构建需注意）");
  }
} else {
  warn("没有 version.properties");
}

// ------------------------------------------------ 8. 括号平衡
console.log("\n[8] 括号平衡");

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

let unbalanced = 0;
for (const p of walk(path.join(ROOT, "app/src")).concat(walk(path.join(ROOT, "band-app/src")))) {
  if (!/\.(kt|ux|js)$/.test(p)) continue;
  const s = fs.readFileSync(p, "utf8");
  const o = (s.match(/\{/g) || []).length;
  const c = (s.match(/\}/g) || []).length;
  if (o !== c) { fail(path.relative(ROOT, p) + " 括号不平衡 " + o + "/" + c); unbalanced++; }
}
if (unbalanced === 0) pass("全部平衡");

// ------------------------------------------------ 汇总
console.log("\n" + "=".repeat(50));
if (errors > 0) {
  console.log("✗ " + errors + " 个错误，不要提交");
  if (warnings > 0) console.log("  （另有 " + warnings + " 个警告）");
  process.exit(1);
} else {
  console.log("✓ 审查通过" + (warnings > 0 ? "（" + warnings + " 个警告）" : ""));
  process.exit(0);
}