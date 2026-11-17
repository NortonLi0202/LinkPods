# LinkPods

简体中文 | [English](README.en.md)

LinkPods 是 Android 上的 AirPods 伴侣应用，提供平板分栏／手机单列的原生设置界面、自绘拖动控件、当前 Android 媒体音量，以及独立耳机控制与可选双向交接的数据路径。

当前版本为 **v1.1 / build102 / lp.pods 预发布**，APK 与校验文件见 [GitHub Release](https://github.com/NortonLi0202/LinkPods/releases/tag/v1.1)。Git 仅收录源码及必要构建资源，不收录旧实验历史、APK、私钥或设备日志。第一阶段独立控制会话（`CONTROL_ONLY`）、路由副作用隔离与分栏状态界面已完成源码与主机测试接入；耳机实际控制听感、双向音频及跨设备稳定性真机验收仍在进行（首轮基线为 `SM-X810`）。已知限制与验收矩阵见 [v1.1 说明](docs/release/v1.1.md) 与 [已知问题](docs/issues/README.md)。

## 功能与要求

- 先直接连接耳机；直连可用时不检查或请求 Root。明确缺少兼容能力、或用户手动选择桥接时，才匹配系统适配并优先复用已有 Root；临时 Root 始终需要单独确认。
- 原生布局随窗口宽度切换，支持浅色／深色、拖动噪声胶囊和椭圆开关，并将**耳机控制**、**系统音频**和**自动交接**状态分开展示。
- 显示耳机已回报的电量与设置；未知（尚未读取）、待确认和不可用状态分开，不使用示例数据冒充状态。
- 支持独立的控制会话模式（`CONTROL_ONLY`）与可选自动交接模式（`AUTO_HANDOFF`）：两者共享单一 AACP 会话；在不授权媒体会话或未开启自动交接时仍可连接、读取状态并控制耳机，切换模式不主动暂停媒体或断开 A2DP。部分高级设置与真机验收仍在进行。
- 从耳机 AACP INFORMATION 帧的专用型号字段识别已固定支持的多代 AirPods，不使用蓝牙显示名称；已知型号只开放其能力集合映射到的现有控制项，未知型号保留通用的报告驱动控制。型号清单与能力边界见 [多代型号说明](docs/airpods-models.md)。
- 会话策略、副作用隔离与多代型号适配已由主机测试覆盖，但尚未完成真机及逐型号物理耳机验收；AirPods Max 不在固定上游 Android 注册表中，仍按未知型号处理。
- Android 12L/API32 起，ARM64。版本下限和已取得 Root 不表示一定有对应 Bluetooth 适配。

连接决策与适配扩展见 [架构及适配说明](docs/adapters.md)。注册表目前仍只含一套真实系统适配；模拟多适配测试不代表新增机型已经支持。

## 构建与检查

```powershell
python tools/project.py check
python tools/project.py test
```

源代码检查和主机测试不需要连接设备。APK 构建另需 Android SDK/NDK/JDK、固定版本的本地二进制依赖及自己的签名配置；这些文件不上传，也不会隐式下载。

```powershell
python tools/project.py check --with-local-inputs
& .\apps\android\build.ps1
```

完整步骤见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 目录

| 路径 | 内容 |
| --- | --- |
| apps/android | 唯一产品应用、原生控件、资源与测试 |
| native/bluetooth | 原生桥接模板、适配信息及主机测试 |
| compatibility | 精确匹配条件和离线能力分类 |
| third_party/sources | 当前构建需要的第三方源码、对应补丁及固定来源 |
| third_party/payloads | 本地输入的 SHA-256 清单；二进制不上传 |
| tools | 检查、归档和构建输入准备工具 |
| docs | 当前版本说明、开发设计和已知问题 |

应用许可证见 [apps/android/LICENSE](apps/android/LICENSE)；组件来源及分发审查范围见 [THIRD_PARTY.md](THIRD_PARTY.md)。
