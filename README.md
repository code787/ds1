# WristControl · 手环遥控手机

用手环遥控手机：触摸板滑动、点击、返回 / 主页 / 多任务、音量、锁屏。

- **手机端**：Android（Kotlin），通过**无障碍服务**执行手势
- **手环端**：小米 Vela 快应用（RPK）
- **通道**：小米官方 interconnect SDK（经「小米运动健康」）

## 它不做什么（重要）

| 不做 | 说明 |
|---|---|
| ❌ 不联网 | 没有任何网络请求，权限清单里连 INTERNET 都没有 |
| ❌ 不读屏幕内容 | 无障碍配置里 `canRetrieveWindowContent="false"` |
| ❌ 不上传数据 | 无服务器、无账号、无授权验证 |
| ❌ 不读其他应用通知 | 与通知监听无关 |

## 架构

```
手环快应用 ──指令 JSON──▶ 小米运动健康 ──SDK──▶ 手机 App
                                                    │
                                              无障碍服务
                                                    │
                                              dispatchGesture
```

**为什么必须走无障碍服务**：Android 上第三方应用无法直接注入触摸事件。
`AccessibilityService.dispatchGesture()` 是唯一不需要 root 的官方途径。

## 当前状态

| 部分 | 状态 |
|---|---|
| 手环端协议与状态逻辑 | ✅ 已完成，10 项单测通过 |
| 手环端界面（触摸板 + 三键） | ✅ 已完成，未上真机 |
| 手机端手势执行 | ✅ 已完成，未编译验证 |
| 手机端 SDK 通道 | ✅ 已完成，未编译验证 |
| 端到端联调 | ❌ **未做**（需要真机 + 编译环境） |

> ⚠️ 本项目**从未编译过**，也**没有在任何真机上跑过**。
> 代码结构、协议、单测是完整的，但能不能跑起来需要你编译后验证。

## 快速开始

### 1. 准备 AAR

```bash
curl -o demo.zip https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip
unzip -j demo.zip 'interconnect_dev_test_demo/libs/xms-wearable-lib_1.4_release.aar' -d app/libs/
```

### 2. 生成签名（APK 与 RPK 必须同一份）

```bash
python3 scripts/gen-signing.py
```

### 3. 编译

```bash
# 手机端（需要 JDK 17 + Android SDK）
./gradlew :app:assembleDebug

# 手环端（需要 Node 20+）
cd band-app && npm install && cd ..
node scripts/build-rpk.js
```

### 4. 安装

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. 手机设置 → 无障碍 → 已下载的服务 → 开启「WristControl 手势服务」
3. 打开 App → 点「启动桥接」
4. 手环：用 AstroBox 或小米运动健康 Debug 入口装 RPK

> ⚠️ **APK 和 RPK 必须来自同一次构建**。证书不一致手环收不到指令，且不会有任何报错——只会静默失败。

## 单测

```bash
cd band-app && npm test
```

覆盖：四向编码、小数精度、音量钳制、帧解析、缺字段默认值、非法输入、状态文案优先级。

## 已知限制

1. **滑动是四向离散的**，不是连续触摸板。手环的 `swipe` 事件只给方向，没有中间态。想要连续光标需要改用 `touchmove` 自己算增量。
2. **没有回执重试**。手环发指令后进「已发送…」，手机执行成功回 ack 才恢复。若指令丢失，手环会停在该状态，需要再操作一次。
3. **无障碍服务被系统杀掉后需要手动重开**，这是 Android 的限制，无法用代码绕过。
4. **锁屏需要 Android 9+**（`GLOBAL_ACTION_LOCK_SCREEN`）。


## 自查记录（2026-11-15）

代码写完后做了一轮逐行自查，修掉了以下问题。**这些是编译期就能发现的错误，说明"写完不编译"确实不可靠。**

| # | 问题 | 后果 | 修复 |
|---|---|---|---|
| 1 | `ControlAccessibilityService` 有**两个 companion object** | Kotlin 编译失败（一个类只允许一个） | 合并成一个，静态状态与查询放一起 |
| 2 | `withGestures` 用了 `inline`，lambda 参数被当值传递 | `when` 表达式里返回类型推断不稳 | 改为普通函数，开销可忽略 |
| 3 | 手环端用了 **ESM**（`import`/`export`） | **Vela 运行时不支持 ESM，整个包跑不起来** | 全部改 CommonJS（`require`/`module.exports`） |
| 4 | `GestureExecutor` 构造时缓存屏幕宽高 | 旋转后手势落点错位 | 每次调用现取 `displayMetrics` |
| 5 | `gen-signing.py` 里 `noclcerts` 拼写错误 | 签名生成失败 | 改为正确的 `-nokeys` |
| 6 | 缺少 Gradle wrapper 配置 | 无法 `./gradlew` 构建 | 补 `gradle/wrapper/gradle-wrapper.properties` |
| 7 | 没请求 `POST_NOTIFICATIONS` | Android 13+ 前台服务通知不显示 | 主页启动时请求运行时权限 |
| 8 | 字符串模板 `\${}` 被误转义 | 日志打出字面量而非变量值 | 全部修正 |

### 仍未验证的

- **Kotlin 从未经过编译器**。以上修复是人工审查的结果，不是编译器验证。
- **Vela 包从未用 aiot-toolkit 打过**。
- **SDK 调用签名**（`Wearable.getNodeApi` 等）参照导航环源码，但那份源码本身在作者机器上跑通过、我这里没有 AAR 可验证。

## 许可

MIT