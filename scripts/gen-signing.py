#!/usr/bin/env python3
"""
生成调试签名：APK 与 RPK 必须共用同一份证书。

小米 interconnect 的硬性要求是「同包名 + 同签名证书」，否则手环收不到消息，
而且不会报错——只会静默失败。所以这两个产物必须来自同一份证书。
"""
import subprocess
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "signing"
OUT.mkdir(parents=True, exist_ok=True)

JKS = OUT / "debug.jks"
PEM_KEY = OUT / "private.pem"
PEM_CRT = OUT / "certificate.pem"
PASS = "wristcontrol"
ALIAS = "wristcontrol"

if JKS.exists() and PEM_KEY.exists() and PEM_CRT.exists():
    print(f"[skip] 证书已存在: {OUT}")
    sys.exit(0)

if not JKS.exists():
    print("[1/3] 生成 keystore...")
    subprocess.run([
        "keytool", "-genkeypair", "-v",
        "-keystore", str(JKS), "-storepass", PASS, "-keypass", PASS,
        "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=WristControl, OU=Dev, O=WristControl, L=NA, ST=NA, C=CN",
    ], check=True)

print("[2/3] 转 PKCS12...")
p12 = OUT / "_tmp.p12"
subprocess.run([
    "keytool", "-importkeystore", "-noprompt",
    "-srckeystore", str(JKS), "-srcstorepass", PASS, "-srcalias", ALIAS,
    "-destkeystore", str(p12), "-deststoretype", "PKCS12",
    "-deststorepass", PASS, "-destkeypass", PASS,
], check=True)

print("[3/3] 导出 PEM...")
# 私钥（不带证书）
subprocess.run([
    "openssl", "pkcs12", "-in", str(p12), "-nocerts", "-nodes",
    "-passin", f"pass:{PASS}", "-out", str(PEM_KEY),
], check=True)
# 证书
subprocess.run([
    "openssl", "pkcs12", "-in", str(p12), "-nokeys",
    "-passin", f"pass:{PASS}", "-out", str(PEM_CRT),
], check=True)

p12.unlink(missing_ok=True)

print(f"[ok] 证书已生成: {OUT}")
print(f"     JKS: {JKS.name}")
print(f"     PEM: {PEM_KEY.name}, {PEM_CRT.name}")
