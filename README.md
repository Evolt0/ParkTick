# ParkTick

面向 Android 的单机停车计时 App：**给车辆拍照，在本机识别并核对车牌，从拍照时刻开始计算停车时长。**

不需要账号或服务端，不调用在线识别接口，不要求 App 常驻后台。

> 当前为开发验证版本，不是正式验收完成版本。业务源码与可安装 APK 已实现，可达环境回归已通过；目标双机实测和识别质量验收仍未关闭，详见[当前限制](#当前限制)。

## 下载测试 APK

**[下载 ParkTick 1.0.0 Preview 1 APK](https://github.com/Evolt0/ParkTick/releases/download/v1.0.0-preview.1/ParkTick-1.0.0-preview.1.apk)** · [预发布说明与 SHA-256 校验文件](https://github.com/Evolt0/ParkTick/releases/tag/v1.0.0-preview.1)

从 Release 的 Assets 下载 `.apk`，不要把 Source code 归档当作安装包。二进制通过 GitHub Releases 分发，不提交到 Git 源码仓库；仓库为私有时，需要登录有访问权限的 GitHub 账号。

这是测试签名的预发布包，用于实机测试，不代表正式验收完成。安装与覆盖升级注意事项见预发布说明；不要为安装直接卸载或清除已有停车数据。

## 主要功能

- **拍照后确认车牌**：识别结果在可编辑弹窗中展示；有误可直接修改，未识别出可填写或重拍，确认前不创建停车记录。
- **从拍照时刻计时**：识别和核对耗时计入停车时长，不从点击确认的时刻重新开始。
- **同牌在停去重**：同一车牌只保留一条进行中的记录；重复登记打开原记录，不替换照片或重置时间。结束后再次登记则创建新记录。
- **确认结束再删照片**：提示“结束后将删除车辆照片”；确认后先保存结束时间与总时长，再删除照片。删除失败如实提示，并在后续回到 App 时重试。
- **本地持久化**：退出、锁屏、终止进程或重启后记录仍保留；同次开机使用单调时钟，重启后按系统时间估算并明确标记，时间不可靠时不伪造时长。
- **照片私有存储**：不自动写入系统相册，数据库与照片排除标准系统备份和迁移；App 不声明 INTERNET 权限。
- **简单界面**：在停列表、拍照、详情、历史四个业务页面；提供明确返回按钮、偏大字体、车牌搜索与历史查询。

卸载或清除应用数据会删除本地记录。照片清理指应用不再保留可访问文件，不承诺闪存取证意义上的不可恢复擦除。

## 技术栈与兼容性

Kotlin、Jetpack Compose / Material 3、CameraX、Room、Coroutines / Flow；车牌识别采用固定源码修订后的 HyperLPR3、MNN、OpenCV 与自有 JNI，六个模型随安装包提供。不是云端识别，也没有后台计时服务。

| 项目 | 配置 |
| --- | --- |
| 最低 Android | Android 7.0，API 24 |
| Compile / Target SDK | API 36 |
| Release ABI | `arm64-v8a`、`armeabi-v7a` |
| Debug ABI | 在 Release ABI 基础上增加 `x86_64` |
| JDK | 17 |
| Gradle Wrapper / AGP | 8.13 / 8.13.2 |
| Android SDK Build Tools | 36.0.0 |
| NDK | 28.2.13676358 |
| CMake | 3.22.1 |

目标使用设备为坚果 Pro 3、Redmi Note 14 Pro **5G**（天玑 7300-Ultra）。这是必测设备清单，不表示已完成两台实测。

## 构建与安装

### 1. 准备环境

克隆仓库后，用 Android Studio 打开项目根目录，将 Gradle JDK 设置为 17，安装上表中的 SDK / NDK / CMake 组件。通过 `ANDROID_HOME` 或本机 `local.properties` 配置 SDK 路径，不要提交机器路径或签名密钥。

下方命令沿用本仓库的 `rtk proxy` 命令代理前缀；未使用 RTK 的环境可直接执行其后的命令，RTK 不是 Android 构建或 App 运行依赖。

首次构建需要下载 Gradle、Maven 依赖及固定版本的 MNN / OpenCV 源码归档；构建过程联网不等于 App 运行联网。依赖版本、锁和校验值均已入库。

### 2. 生成可安装 Debug APK

在项目根目录执行：

```bash
rtk proxy ./gradlew --no-daemon :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

手机开启开发者选项和 USB 调试后，先确认设备，再安装；将 `<serial>` 替换为实际设备序列号：

```bash
rtk proxy adb devices -l
rtk proxy adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
rtk proxy adb -s <serial> shell am start -n com.evolto.parktick/.MainActivity
```

首次拍照需要授予相机权限。

### 3. Release 构建

```bash
rtk proxy ./gradlew --no-daemon :app:assembleRelease
```

默认产物 `app/build/outputs/apk/release/app-release-unsigned.apk` **未签名，不能直接安装**。测试签名、签名验证及生产密钥边界见[构建与安装手册](docs/runbooks/android-build.md)。不要把测试证书当成正式分发签名。

## 回归检查

本地规则检查及 Android 测试包构建：

```bash
rtk proxy ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

连接测试设备或模拟器后，安装上述 Debug APK 和测试包，再执行：

```bash
rtk proxy adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
rtk proxy adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
rtk proxy adb -s <serial> shell am instrument -w com.evolto.parktick.test/androidx.test.runner.AndroidJUnitRunner
```

结果以 runner 输出的 `OK` / `FAILURES` 为准，不只看 ADB 退出码。普通 Debug 测试包不能直接当作混淆 Release 的测试包使用。

现有回归包含计时和归一化规则、真实 Room / 文件生命周期与生产模型识别。实际执行结果、故障注入、R8 / 16KB 证据和 APK SHA-256 统一记录在[运行验证记录](docs/reference/verification.md)，不将编译成功等同于完整验收。

## 代码结构

```text
app/
  src/main/java/com/evolto/parktick/
    camera/        CameraX 拍照与权限交互
    data/          Room、登记去重与结束事务
    domain/        停车记录与时长模型
    media/         私有照片、解码与文件清理
    recognition/   模型初始化与本机识别
    time/          计时锚点与重启估算
    ui/            页面、弹窗与业务状态
  src/main/cpp/    JNI 与固定源码依赖构建
  src/main/assets/ 模型与第三方许可
  src/test/        本地规则回归
  src/androidTest/ Android 数据与真实识别回归
docs/             需求、技术方案、实施计划和验证记录
third_party/      HyperLPR 修订源码与物料来源锁
gradle/           Wrapper、版本目录与依赖校验元数据
```

## 项目文档

- [文档总览](docs/INDEX.md)
- [需求与验收标准](docs/requirements/planned/parking-timer.md)
- [技术方案](docs/design/technical-solution.md)
- [实施计划与当前进度](docs/requirements/planned/implementation-plan.md)
- [构建、安装与签名](docs/runbooks/android-build.md)
- [运行验证与未关闭条件](docs/reference/verification.md)
- [变更记录](docs/reference/changelog.md)

## 当前限制

- 坚果 Pro 3、Redmi Note 14 Pro 5G 尚未连接验证，实车相机、输入法、ROM、性能与重启行为仍需分别验收。
- 公开固定挑战集仍有误识别和未检出，尤其是低清、小车牌和暗光场景；识别结果必须核对。样本统计和偏差见运行验证记录，不能当作产品泛化准确率。
- 普通黄牌的合法标注样本库尚未补齐；原生 ARM64 16KB、ARMv7 与双机持续运行仍待验证。现有 ARM64 翻译环境的运行证据不代替原生 ARM 真机。
- 应用被强制停止或不再运行时，失败的照片删除任务没有完成时限；下次运行恢复处理。不增加常驻后台服务来掩盖该系统边界。
- 正式分发仍需项目所有者管理生产签名，并确认模型及相关分发权利；不把归档上游许可证等同于完成训练数据权利审计。

## 第三方来源与许可

源码、模型版本与 SHA-256 见 [`third_party/sources.lock.json`](third_party/sources.lock.json)。HyperLPR 许可证位于 [`third_party/hyperlpr/LICENSE`](third_party/hyperlpr/LICENSE)，其余打包组件的许可证和修订声明位于 [`app/src/main/assets/licenses/`](app/src/main/assets/licenses/)。第三方代码与模型分别遵循其适用声明，不将上游许可证扩写为整个项目统一授权。
