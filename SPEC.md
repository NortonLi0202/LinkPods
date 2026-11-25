# Specification

本规格描述 LinkPods 当前源码的可见行为与验收边界，用于开发与回归。模块实现与边界见 [ARCHITECTURE.md](ARCHITECTURE.md)，领域术语见 [GLOSSARY.md](GLOSSARY.md)，维护约束见 [AGENTS.md](AGENTS.md)，专题文档索引见 [docs/README.md](docs/README.md)。

> **当前状态与真机边界**：当前源码基线为 `v1.1 / build102` 预发布，尚无声明稳定支持的生产配置。本规格记录的“当前源码行为”不等于已在所有目标设备上完成耳机实际听感、双向音频或跨设备稳定性真机验收；真机验收状态以 [docs/issues/README.md](docs/issues/README.md) 与当前版本说明 [docs/release/v1.1.md](docs/release/v1.1.md) 为准。仓库仅保存源码与必要构建资源，不包含个人设备运行状态。

## 1. 产品与平台范围

- 面向 Android（重点适配三星 Galaxy 与 One UI）的原生 AirPods 伴侣应用，采用分层设计：独立 AACP 耳机控制、按需匹配的兼容桥接、跨设备音频交接。
- 系统与架构下限：Android 12L（API 32）+，ARM64。满足系统下限或拥有 Root 权限不代表已有对应的蓝牙系统适配。
- 安全与设备边界：禁止刷机、刷 bootloader、解锁与永久 Root；禁止刷写/擦除块设备或重挂系统分区。设备操作需显式授权。

## 2. 连接模式与交接行为

- **控制与交接解耦**：`CONTROL_ONLY` 与 `AUTO_HANDOFF` 共享单一 AACP 会话。
  - 未授予媒体通知权限或关闭自动交接时，仍可连接耳机、读取状态并切换降噪等基础控制。
  - 切换或降级模式不会主动暂停正在播放的媒体，也不会主动断开已有 A2DP 连接。
- **直连优先，按需桥接**：
  - 默认直接建立 AACP 控制会话，直连可用时不检测或请求 Root，也不加载桥接。
  - 普通超时、连接被拒绝、权限失败或缺少应用 API 不作为缺少 Root 的证据，报告失败并允许重试。
  - 仅在明确的协议不支持错误下允许一次自动已有 Root 桥接回退；自动流程永不触发临时 Root，临时 Root 必须由用户单独确认。
  - 适配匹配规则与限制见 [docs/adapters.md](docs/adapters.md)。
- **iOS 直播强抢退让**：遇到 iPhone 活跃直播会话在交接后强行抢回耳机时，经单次复核后干净让出，避免乒乓死循环。

## 3. 型号识别与状态展示

- **协议级型号识别**：基于 AACP `INFORMATION` 专用型号字段匹配固定注册表，不依赖蓝牙显示名称；按型号能力开放控制项。未知型号保留通用报告驱动控制（详见 [docs/airpods-models.md](docs/airpods-models.md)）。
- **状态区分**：电量与设置严格区分“尚未读取”“待确认”与“不可用”；蓝牙断开重连或更换耳机目标时清除旧状态，不串用旧会话。
- **自适应界面**：随窗口宽度在平板分栏与手机单列间切换，支持浅色/深色主题，并将**耳机控制**、**系统音频**与**自动交接**状态分开展示。

## 4. 验收基线

- **主机侧基线**：常规变更须通过 `python tools/project.py check` 与 `python tools/project.py test`。
- **真机侧基线**：以 `SM-X810` 为首轮真机验收基线，场景列表与当前状态见 [docs/issues/README.md](docs/issues/README.md)；主机测试通过不替代真实耳机听感与跨设备验收。
