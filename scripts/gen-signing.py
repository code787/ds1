#!/usr/bin/env python3
"""签名证书生成（幂等）。

**关键约束**：APK 与 RPK 必须用同一份证书签名，否则小米互联校验失败
（SignatureVerifyFailedException）。

因此证书一旦生成就必须固定下来、随仓库分发（见 app/signing/README.md）。
本脚本只在证书缺失时生成，已存在时直接跳过——绝不能每次构建都换新证书，
否则手环上装的 RPK 会和手机上装的 APK 对不上。

只用 openssl，不依赖 keytool（CI 与本机都能跑）。
"""
import subprocess
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "signing"

PEM_KEY = OUT / "private.pem"
PEM_CRT = OUT / "certificate.pem"
P12 = OUT / "debug.p12"
PASS = "wristcontrol"

# 30 年：够这个项目用到废弃
DAYS = 10950
SUBJ = "/CN=WristControl/OU=Dev/O=WristControl/C=CN"


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)

    have_key = PEM_KEY.exists()
    have_crt = PEM_CRT.exists()
    have_p12 = P12.exists()

    if have_key and have_crt and have_p12:
        print(f"[skip] 证书已存在，保持不变: {OUT}")
        show_fingerprint()
        return 0

    if have_key or have_crt:
        # 半套证书是最危险的状态：说明有人删了其中一部分，
        # 继续生成会导致新旧证书混用。宁可失败也不猜。
        print(
            "[错误] 证书不完整（private.pem / certificate.pem / debug.p12 只存在一部分）。\n"
            "       请把 app/signing/ 下的三个文件一起恢复，或一起删除后重新生成。\n"
            "       混用证书会导致手环收不到消息，且不会有明显报错。",
            file=sys.stderr,
        )
        return 1

    print("[1/2] 生成私钥与自签名证书…")
    subprocess.run(
        [
            "openssl", "req", "-x509", "-newkey", "rsa:2048",
            "-keyout", str(PEM_KEY), "-out", str(PEM_CRT),
            "-days", str(DAYS), "-nodes", "-sha256", "-subj", SUBJ,
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    print("[2/2] 打包成 PKCS12（AGP 可直接当 keystore 用）…")
    subprocess.run(
        [
            "openssl", "pkcs12", "-export",
            "-in", str(PEM_CRT), "-inkey", str(PEM_KEY),
            "-out", str(P12), "-name", "wristcontrol",
            "-passout", f"pass:{PASS}",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    print(f"[ok] 证书已生成: {OUT}")
    show_fingerprint()
    return 0


def show_fingerprint() -> None:
    """打印指纹，方便肉眼核对 APK 与 RPK 是否同源。"""
    try:
        out = subprocess.run(
            ["openssl", "x509", "-in", str(PEM_CRT), "-noout", "-fingerprint", "-sha256"],
            check=True, capture_output=True, text=True,
        )
        print("     指纹: " + out.stdout.strip().replace("sha256 Fingerprint=", ""))
    except Exception:
        pass


if __name__ == "__main__":
    sys.exit(main())
