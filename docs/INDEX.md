# ParkTick 文档

## 需求

- [停车计时 App 需求文档](requirements/planned/parking-timer.md)：Android 单机离线车牌识别与停车计时，包含拍照后可编辑的车牌确认弹窗、简单明了与大字可返回的交互基线、业务规则、页面定义、数据需求、需求确认记录和验收场景。

## 技术方案

- [Android 技术方案](design/technical-solution.md)：框架与版本基线、离线识别源码修订门禁、拍照计时、事务去重、照片清理、隐私与实际证据。

## 实施计划

- [实施计划](requirements/planned/implementation-plan.md)：M0a 构建环境、M0b 真实离线识别验证，以及数据、拍照登记、完整闭环、可靠性和交付阶段的依赖与验收要求。

## 当前阶段

原生 Android 工程、停车业务源码、自有离线识别 JNI 与随包模型已实现，Debug、测试及混淆 Release APK 已构建。仍处于实施验证，不是正式验收完成状态。

原绿牌漏字案例已通过精确回归；公开挑战照片仍有误识别与未检出，普通黄牌授权样本库及两台目标手机验收尚未关闭。API 24、API 36、16KB 模式与 ARM64 翻译运行的证据分别记录，不用模拟器或手动修正代替目标真机与识别效果验收。

## 构建、验证与变更

- [构建与安装](runbooks/android-build.md)：工具链、APK 路径、签名边界和复现命令。
- [运行验证记录](reference/verification.md)：实际执行结果、A-01 至 A-23 的证据边界与未关闭门禁。
- [变更记录](reference/changelog.md)：本次实现、修复及兼容性变化。
