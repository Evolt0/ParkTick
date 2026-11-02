# ParkTick 构建与安装

- 日期：2026-10-31
- [技术方案](../design/technical-solution.md) · [运行验证与阻塞](../reference/verification.md)
- 当前是开发验证构建，识别与目标真机门禁尚未全部通过，不作为正式发布或验收完成版本。

## 1. 环境

JDK 17、Android SDK Platform / Build Tools 36、NDK `28.2.13676358`、CMake `3.22.1`。Gradle Wrapper 固定 8.13；AGP、Kotlin、KSP 和 AndroidX 版本在 `gradle/libs.versions.toml`，解析结果与校验值在 `app/gradle.lockfile`、`gradle/verification-metadata.xml`。

在 Android Studio 中设置 Gradle JDK 为 17，并安装上述 SDK 组件。SDK 本机路径使用忽略的 `local.properties` 或 `ANDROID_HOME`；不要提交机器路径、签名密钥、`.tooling/`、`.gradle/`、`.cxx/`、构建目录。

## 2. 构建

在项目根目录，配置好 JDK / SDK 后执行：

```bash
rtk proxy ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

本次实际构建使用经过官方 SHA-256 校验的私有 Gradle 分发，未修改全局 Java。对应本机相对路径命令为：

```bash
rtk proxy env JAVA_HOME="$PWD/.tooling/jdk-17.0.21+9/Contents/Home" ANDROID_HOME="$PWD/.tooling/android-sdk" GRADLE_USER_HOME="$PWD/.tooling/gradle-user-home" .tooling/gradle-8.13/bin/gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

Wrapper 由 Gradle 正式生成并校验，不是手写替代脚本。首次构建需要获取 Gradle、Maven 和固定源码归档；**构建机下载依赖不等于 App 运行时联网**。App 不声明 INTERNET，模型随安装包提供。

Native 在单个 `:app` 中源码构建。MNN / OpenCV 归档 URL 与 SHA-256 由 CMake 固定；可预置相同校验值的 `.tooling/downloads/mnn.tar.gz`、`opencv.tar.gz`，否则从固定 URL 获取。HyperLPR 修订源码在 `third_party/hyperlpr`，物料与六个模型校验值在 `third_party/sources.lock.json`，许可随 APK 的 `assets/licenses/` 分发。

## 3. 产物与签名

| 产物 | 用途与边界 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 可安装测试 APK，标准 debug 签名；包含 ARM64、ARMv7、x86_64 |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | Android 实机 / 模拟器回归测试包，不是用户 App |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | ARM64 / ARMv7、启用 R8 与资源收缩；未签名，不能直接安装 |
| `app/build/outputs/apk/release/app-release-test-signed.apk` | 使用测试证书签名的 Release 验证包；仅当本地签名步骤已执行时存在，不是生产密钥签名 |

需要复现测试签名 Release 时，可使用 Gradle 已生成的本机 debug 证书；下例沿用本次私有 JDK 路径，其他环境替换为自己的 JDK 17：

```bash
rtk proxy env JAVA_HOME="$PWD/.tooling/jdk-17.0.21+9/Contents/Home" .tooling/android-sdk/build-tools/36.0.0/apksigner sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out app/build/outputs/apk/release/app-release-test-signed.apk app/build/outputs/apk/release/app-release-unsigned.apk
rtk proxy env JAVA_HOME="$PWD/.tooling/jdk-17.0.21+9/Contents/Home" .tooling/android-sdk/build-tools/36.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release-test-signed.apk
```

正式分发需由项目所有者管理独立生产签名与后续升级密钥，不把测试密钥当成正式密钥。Debug 与 Release 的 ABI 不同：仅运行 x86_64 Debug 不能证明 ARM Release JNI / 混淆通过。本次另在带 ARM64 翻译支持的 API 35 16KB 环境安装混淆 Release，执行真实 JNI 识别及实际启动 / 相机 / 弹窗；这证明该翻译环境中的运行，不代替原生 ARM 手机验收。

## 4. 安装与执行回归

先确认序列号，避免对错误设备操作：

```bash
rtk proxy adb devices -l
rtk proxy adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
rtk proxy adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
rtk proxy adb -s <serial> shell am instrument -w com.evolto.parktick.test/androidx.test.runner.AndroidJUnitRunner
rtk proxy adb -s <serial> shell am start -n com.evolto.parktick/.MainActivity
```

本次使用 `.tooling/android-sdk/platform-tools/adb`。JUnit 结果以 runner 的 `OK` / `FAILURES` 文本为准，不能仅看 `adb` 进程退出码。最终 API 24、API 36 和 API 35 16KB 环境各 16 项检查通过；这是同一组检查在三个环境执行，不是 48 项独立用例。原绿牌漏字固定样本已通过，但挑战集仍存在明显误识别，不能把固定样本通过等同于真实场景识别质量达标。

混淆 Release 另用临时 Java 平台 Instrumentation 按 R8 映射读取结果，验证精确蓝牌 / 绿牌、置信度及 JNI 构造器；诊断源码与 runner 覆盖已移除。普通 Debug AndroidX 测试包不能直接当作混淆 Release 的测试包：其 Kotlin 运行时引用会受目标 APK 混淆影响。不要为让诊断包运行而扩大生产 keep 规则；当前正式测试仍使用上面的标准 runner。

先在手机断网状态验证首次启动和真实拍照，再核对弹窗、编辑、重复登记、结束确认、历史及重启。卸载或清除应用数据会删除本地停车数据；不要为排错直接清空使用中的手机数据。

## 5. Native 与 APK 静态检查

```bash
rtk proxy .tooling/android-sdk/build-tools/36.0.0/zipalign -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
rtk proxy .tooling/android-sdk/build-tools/36.0.0/zipalign -c -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk
rtk proxy .tooling/android-sdk/build-tools/36.0.0/aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```

检查 APK 中**每个** 64 位 `.so` 的所有 `PT_LOAD` 段，不能只检查自有识别库。ARMv7 的 NDK 32 位 libc++ 为 4KB 对齐，单独记录，不混入 64 位 16KB 运行结论。静态对齐通过仍须在 ARM / 16KB 环境执行冷启动与重复识别。
