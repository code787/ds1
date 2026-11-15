# 构建说明

## 前置

- JDK 17
- Android SDK（compileSdk 36）
- Node.js 20+（手环端）
- 小米官方 `xms-wearable-lib_1.4_release.aar`

## 第一步：拿到 AAR（不随仓库分发）

从官方示例包提取：

```bash
curl -o demo.zip https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip
unzip -j demo.zip 'interconnect_dev_test_demo/libs/xms-wearable-lib_1.4_release.aar' -d app/libs/
```

校验（应为 102453 字节）：

```bash
sha256sum app/libs/xms-wearable-lib_1.4_release.aar
# 9c40fd1c5409bb948523d503af71e2978ae522c35636afe7d64f474c0f6bc195
```

## 第二步：生成签名证书（APK 与 RPK 必须同一份）

```bash
python3 scripts/gen-signing.py
```

会在 `app/signing/` 生成 `debug.jks`、`private.pem`、`certificate.pem`。
**这三个文件不要提交到 git。**

## 第三步：编译手机端

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

## 第四步：编译手环端

```bash
cd band-app && npm install
cd .. && node scripts/build-rpk.js
# 产物 band-app/dist/com.wristcontrol.app.debug.0.1.0.rpk
```

## 第五步：安装

1. 手机：`adb install -r app-debug.apk`
2. 手机设置 → 无障碍 → 已下载的服务 → 开启「WristControl」
3. 手环：AstroBox 或小米运动健康 Debug 入口安装 RPK

> ⚠️ APK 和 RPK 必须**同时**来自你这次的构建。证书不一致手环收不到指令。
