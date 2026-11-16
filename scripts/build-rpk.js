#!/usr/bin/env node
/**
 * 构建手环端 RPK。
 *
 * 版本号从根目录 version.properties 读取并注入 manifest.json，
 * 保证 APK 与 RPK 的 versionCode 始终一致——这是小米互联的要求之一。
 *
 * 用法：
 *   node scripts/build-rpk.js              # 用 version.properties 里的版本
 *   node scripts/build-rpk.js --code 15    # 临时指定 versionCode（CI 用构建号）
 */
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.resolve(__dirname, "..");
const BAND = path.join(ROOT, "band-app");
const DIST = path.join(BAND, "dist");
const MANIFEST = path.join(BAND, "src", "manifest.json");

function fail(msg) {
  console.error("[失败] " + msg);
  process.exit(1);
}

/** 读 version.properties */
function readVersion() {
  const f = path.join(ROOT, "version.properties");
  const out = { versionCode: 1, versionName: "0.1.0" };
  if (!fs.existsSync(f)) return out;
  for (const line of fs.readFileSync(f, "utf8").split("\n")) {
    const t = line.trim();
    if (!t || t.startsWith("#")) continue;
    const i = t.indexOf("=");
    if (i < 0) continue;
    const k = t.slice(0, i).trim();
    const v = t.slice(i + 1).trim();
    if (k === "versionCode") out.versionCode = parseInt(v, 10) || 1;
    if (k === "versionName") out.versionName = v;
  }
  return out;
}

/** 解析命令行参数 */
function argValue(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : null;
}

// 1. 确定版本
const fromProps = readVersion();
const codeOverride = argValue("--code");
const nameOverride = argValue("--name");
const versionCode = codeOverride ? parseInt(codeOverride, 10) : fromProps.versionCode;
const versionName = nameOverride || fromProps.versionName;

if (!Number.isFinite(versionCode) || versionCode < 1) {
  fail("versionCode 非法: " + versionCode);
}

// 2. 注入 manifest
let manifestRaw;
try {
  manifestRaw = fs.readFileSync(MANIFEST, "utf8");
} catch (e) {
  fail("读不到 manifest.json");
}

let manifest;
try {
  manifest = JSON.parse(manifestRaw);
} catch (e) {
  fail("manifest.json 不是合法 JSON: " + e.message);
}

const original = JSON.stringify(manifest);
manifest.versionCode = versionCode;
manifest.versionName = versionName;

if (JSON.stringify(manifest) !== original) {
  fs.writeFileSync(MANIFEST, JSON.stringify(manifest, null, 2) + "\n");
}
console.log("[版本] RPK versionCode=" + versionCode + " versionName=" + versionName);

// 3. 检查证书
const signDir = path.join(BAND, "sign", "debug");
const keyPem = path.join(ROOT, "app", "signing", "private.pem");
const crtPem = path.join(ROOT, "app", "signing", "certificate.pem");

if (!fs.existsSync(keyPem) || !fs.existsSync(crtPem)) {
  fail("缺少签名证书，先运行: python3 scripts/gen-signing.py");
}

fs.mkdirSync(signDir, { recursive: true });
fs.copyFileSync(keyPem, path.join(signDir, "private.pem"));
fs.copyFileSync(crtPem, path.join(signDir, "certificate.pem"));

// 4. 检查 toolkit
const cli = path.join(BAND, "node_modules", "aiot-toolkit", "lib", "bin.js");
if (!fs.existsSync(cli)) {
  fail("缺少 aiot-toolkit，先运行: cd band-app && npm install");
}

// 5. 构建
fs.mkdirSync(DIST, { recursive: true });
console.log("[构建] 开始打包 RPK...");
try {
  execFileSync(process.execPath, [cli, "build"], {
    cwd: BAND,
    stdio: "inherit",
    timeout: 180000,
  });
} catch (e) {
  fail("aiot-toolkit 构建失败: " + e.message);
}

// 6. 找产物
const produced = fs.readdirSync(DIST).filter((f) => f.endsWith(".rpk"));
if (produced.length === 0) fail("构建成功但未找到 .rpk 产物");

const rpk = path.join(DIST, produced[0]);
const bytes = fs.statSync(rpk).size;
const crypto = require("node:crypto");
const sha256 = crypto.createHash("sha256").update(fs.readFileSync(rpk)).digest("hex");

console.log("[成功] " + rpk);
console.log("       大小: " + bytes + " 字节");
console.log("       SHA256: " + sha256);
console.log("       versionCode: " + versionCode);
