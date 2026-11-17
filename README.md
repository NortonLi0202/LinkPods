# LinkPods

LinkPods 是 Android 上的 AirPods 伴侣应用，提供平板分栏／手机单列的原生设置界面、自绘拖动控件、当前 Android 媒体音量，以及耳机设置和双向交接的数据路径。

本仓库仅包含 **v1.0 / build101 / lp.pods 的源码快照**，不包含旧实验历史、APK、私钥或设备日志。当前版本尚未完成耳机控制、双向音频及跨设备稳定性验收；已知限制见 [v1.0 说明](docs/release/v1.0.md)。

## 功能与要求

- 先直接连接耳机；直连可用时不检查或请求 Root。明确缺少兼容能力、或用户手动选择桥接时，才匹配系统适配并优先复用已有 Root；临时 Root 始终需要单独确认。
- 原生布局随窗口宽度切换，支持浅色／深色、拖动噪声胶囊和椭圆开关。
- 显示耳机已回报的电量与设置；未知、待确认和不可用状态分开，不使用示例数据冒充状态。
- 从耳机 AACP INFORMATION 帧的专用型号字段识别已固定支持的多代 AirPods，不使用蓝牙显示名称；已知型号只开放其能力集合映射到的现有控制项，未知型号保留通用的报告驱动控制。型号清单与能力边界见 [多代型号说明](docs/airpods-models.md)。
- 当前耳机设置依赖已启用的交接服务；独立控制会话和部分高级设置尚未完成。多代型号适配已由主机测试覆盖，但尚未完成逐型号真机验收；AirPods Max 不在固定上游 Android 注册表中，仍按未知型号处理。
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
