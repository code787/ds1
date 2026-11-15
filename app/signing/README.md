# 签名证书（故意入库）

本目录的证书**必须提交到仓库**，不要加进 .gitignore。

## 为什么

小米官方 interconnect 的硬性要求：

> interconnect 通信前提要保证快应用和三方应用安卓端两者的包名及签名保持一致。

也就是说 **APK 和 RPK 必须是同一把证书签的**。任何一边换了证书，两边就通信不了。

失败的典型表现是手机端报：

```
SignatureVerifyFailedException
```

**而且没有任何其它提示**——手环上装好了、App 也运行了、权限也给了，就是不工作。

## 曾经踩过的坑

最初的 CI 每次构建都重新生成证书（因为 `app/signing/` 被 gitignore 忽略，CI 又是全新 checkout）。
结果：手环上装的是 A 证书的 RPK，手机上装的是 B 证书的 APK，两者对不上，通信永远失败。

现在证书固定入库，每次构建复用同一把。

## 文件

| 文件 | 用途 |
|---|---|
| `private.pem` | RSA 私钥，openssl 生成 |
| `certificate.pem` | 自签名证书，有效期 30 年 |
| `debug.p12` | PKCS12 打包，AGP 直接当 keystore 用（口令 `wristcontrol`） |

## 指纹

```bash
openssl x509 -in app/signing/certificate.pem -noout -fingerprint -sha256
```

核对 APK 与 RPK 是否同源：

```bash
# APK
apksigner verify --print-certs app-debug.apk
# RPK 解包看 META-INF/CERT
```

## 安全说明

这是**调试证书**，只用于个人自用与联调，不是生产密钥。
口令硬编码在 `app/build.gradle.kts` 里是有意为之——它保护不了什么，
真正的安全边界是"这个 App 不联网、不上传数据、只做本地手势"。

## 换证书的代价

一旦更换，必须：

1. 重新构建 APK 和 RPK
2. **卸载手机上的旧 APK**（签名变了不能覆盖安装）
3. **卸载手环上的旧 RPK**
4. 两个都重新安装

所以除非万不得已，别动这里的文件。
