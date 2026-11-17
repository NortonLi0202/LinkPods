# LinkPods

简体中文 | [English](README.en.md)

**LinkPods** 是一款面向 Android（重点适配三星 Galaxy 与 One UI）的原生 AirPods 伴侣应用。它不通过伪装成 Galaxy Buds 或强制依赖 Root 来工作，而是将独立的 AACP 耳机控制、按需匹配的兼容桥接与跨设备音频交接分层解耦，让 AirPods 在 Android 上获得清晰、可控的原生使用体验。

> **当前状态**：当前版本为 **v1.1 / build102（`lp.pods`）预发布**，APK 与校验文件见 [GitHub Release](https://github.com/NortonLi0202/LinkPods/releases/tag/v1.1)。Git 仅收录源码及必要构建资源，不收录旧实验历史、APK、私钥或设备日志。第一阶段独立控制会话（`CONTROL_ONLY`）、路由副作用隔离与多代型号能力门控已完成源码实现与主机测试；耳机实际听感、双向音频与跨设备稳定性真机验收（以 `SM-X810` 为首轮基线）仍在进行中。详见 [v1.1 说明](docs/release/v1.1.md) 与 [已知问题](docs/issues/README.md)。

## 核心特性

- **自适应原生界面**：随窗口宽度在平板分栏与手机单列间切换，支持浅色／深色主题、自绘可拖动降噪胶囊、椭圆开关以及当前 Android 媒体音量控制，并将**耳机控制**、**系统音频**与**自动交接**状态分开展示。
- **控制与交接解耦**：`CONTROL_ONLY` 与 `AUTO_HANDOFF` 共享单一 AACP 会话。即使不授予媒体通知权限或关闭自动交接，仍可连接耳机、读取状态并切换降噪；切换或降级模式不会主动暂停媒体或断开已有 A2DP 连接。
- **直连优先，按需桥接**：默认直接连接耳机，直连可用时不检测或请求 Root。仅在确认缺少系统兼容能力或用户手动准备桥接时，才按精确注册表匹配系统适配并优先复用已有 Root；临时 Root 始终需单独确认。
- **AACP 协议级型号与状态读取**：基于 AACP `INFORMATION` 专用型号字段识别固定注册表中的多代 AirPods（不依赖蓝牙显示名称），按型号能力开放对应控制项；未知型号（含尚未纳入上游注册表的 AirPods Max）保留通用的报告驱动控制，电量与设置严格区分“尚未读取”“待确认”与“不可用”。

## 运行要求

- **系统与架构**：Android 12L (API 32)+，ARM64
- **兼容性边界**：满足系统下限或拥有 Root 权限并不代表已有对应蓝牙系统适配；目前适配器注册表仅包含一套真实系统适配，详情见 [架构及适配说明](docs/adapters.md)。

## 构建与检查

主机源码校验与测试无需连接设备：

```powershell
python tools/project.py check
python tools/project.py test
```

完整构建 APK 另需 Android SDK/NDK/JDK、固定版本的本地二进制依赖及本地签名配置（这些文件不纳入 Git，也不会隐式下载），完整步骤见 [CONTRIBUTING.md](CONTRIBUTING.md)：

```powershell
python tools/project.py check --with-local-inputs
& .\apps\android\build.ps1
```

## 目录结构

| 路径 | 说明 |
| --- | --- |
| `apps/android` | Android 主应用源码、原生控件、资源与测试 |
| `native/bluetooth` | 原生桥接模板、适配配置及主机测试 |
| `compatibility` | 精确匹配条件与离线能力分类 |
| `third_party/sources` | 构建所需的第三方源码、补丁与固定来源 |
| `third_party/payloads` | 本地二进制输入的 SHA-256 清单（二进制本身不上传） |
| `tools` | 项目检查、归档与构建输入准备脚本 |
| `docs` | 版本说明、架构设计、型号边界与已知问题 |

## 文档导航

- [v1.1 版本说明](docs/release/v1.1.md) — 更新内容、安装说明与已知限制
- [已知问题与验收矩阵](docs/issues/README.md) — 待验证场景与 `SM-X810` 测试矩阵
- [多代型号说明](docs/airpods-models.md) — AACP 型号注册表与能力门控规则
- [架构及适配说明](docs/adapters.md) — 直连决策、桥接匹配与适配扩展方式
- [构建与贡献指南](CONTRIBUTING.md) — 本地依赖准备、签名与提交流程

## 许可与致谢

应用许可证见 [apps/android/LICENSE](apps/android/LICENSE)；第三方组件来源与分发审查范围见 [THIRD_PARTY.md](THIRD_PARTY.md)。
