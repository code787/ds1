#!/usr/bin/env node
/**
 * 构建手环端 RPK。
 *
 * 依赖 aiot-toolkit（小米官方快应用打包工具），需先在 band-app 下 npm install。
 * 签名证书必须与手机 APK 同源，否则手环收不到指令。
 */
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.resolve(__dirname, "..");
const BAND = path.join(ROOT, "band-app");
const DIST = path.join(BAND, "dist");

function fail(msg) {
  console.error("[失败] " + msg);
  process.exit(1);
}

// 1. 检查证书
const signDir = path.join(BAND, "sign", "debug");
const keyPem = path.join(ROOT, "app", "signing", "private.pem");
const crtPem = path.join(ROOT, "app", "signing", "certificate.pem");

if (!fs.existsSync(keyPem) || !fs.existsSync(crtPem)) {
  fail("缺少签名证书，先运行: python3 scripts/gen-signing.py");
}

fs.mkdirSync(signDir, { recursive: true });
fs.copyFileSync(keyPem, path.join(signDir, "private.pem"));
fs.copyFileSync(crtPem, path.join(signDir, "certificate.pem"));

// 2. 检查 toolkit
const cli = path.join(BAND, "node_modules", "aiot-toolkit", "lib", "bin.js");
if (!fs.existsSync(cli)) {
  fail("缺少 aiot-toolkit，先运行: cd band-app && npm install");
}

// 3. 构建
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

// 4. 找产物
const produced = fs.readdirSync(DIST).filter((f) => f.endsWith(".rpk"));
if (produced.length === 0) fail("构建成功但未找到 .rpk 产物");

const rpk = path.join(DIST, produced[0]);
const bytes = fs.statSync(rpk).size;
const crypto = require("node:crypto");
const sha256 = crypto.createHash("sha256").update(fs.readFileSync(rpk)).digest("hex");

console.log("[成功] " + rpk);
console.log("       大小: " + bytes + " 字节");
console.log("       SHA256: " + sha256);
