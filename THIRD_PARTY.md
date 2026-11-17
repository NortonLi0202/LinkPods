# 第三方来源及许可证

此文件不授予新的整体开源许可证，也不代表完成可公开分发审查。保留组件原 LICENSE 和声明。

v1.0 安装包包含 DFRoot v4.7 的路径重定位 ELF、GPL-2.0 诊断辅助源及显式 KernelSU 模块/loader。原生 bootstrap 使用 GPL-2.0-only 声明并保留许可文本；APK 附 DFRoot LICENSE。它们是独立进程/载荷，未将 GPL-2.0 的内核源码并入 Java 业务库。整体分发审查仍未完成，当前不对外发布。固定输入与编译过程见 tools/standalone_payload.py、应用 build.ps1 及发布清单。

| 组件 | 来源 | 管理 |
| --- | --- | --- |
| DFRoot | https://github.com/diabl0w/DFRoot | third_party/sources 固定来源与必要源码；原许可为准 |
| KernelSU | https://github.com/diabl0w/KernelSU | 同上，保留内核原有许可 |
| LibrePods | https://github.com/librepods-org/librepods | 同上；handoff 应用保留 GPL-3.0 文本 |
| HiddenApiBypass 6.2 | https://github.com/LSPosed/AndroidHiddenApiBypass | lib 的 JAR 与 Apache-2.0 文本保留，APK 带许可 |
| Frida inject | https://github.com/frida/frida/releases | third_party/payloads 清单固定 SHA-256，大二进制只在本地；发布前复核发行许可 |
| SDK、JDK、Node、Python | 外部构建工具 | 不复制入管理仓库 |

多代型号移植对应 LibrePods 固定提交 `89356c08cea3db0b6b54792d9329d09a87cc85fa` 的 `android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt` 与 `android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt`，仅移植型号注册表、能力位和 INFORMATION 兼容解析，保留 GPL-3.0-or-later 来源与许可声明；本地 Java 文件头及 [docs/airpods-models.md](docs/airpods-models.md) 记录同样来源。没有新增二进制、图片或未在固定注册表中的型号。

维护方式参考 [GitHub CLI 贡献指南](https://github.com/cli/cli/blob/trunk/.github/CONTRIBUTING.md) 的范围明确的贡献、验证及问题说明。仅采用实际需要的流程，没有虚构组织治理、CI 和发布状态。
