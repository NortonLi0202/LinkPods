# 连接能力与系统适配

公共协议、播放策略和系统适配分别维护。`ConnectionPolicy` 的动作驱动 `HandoffService`；它决定连接、重连、请求桥接或报告缺少能力，不只打印分类日志。`RoutingPackets`、`RoutingPolicy`、`PeerRoutingPolicy`、`EarbudCommands` 保持公共代码，不包含机型偏移。

## 连接决策

1. 启用连接时直接启动 AACP 会话，不构造 Root 资源、不运行 su、不准备桥接。
2. 直连成功就保持直连；断开后直接重连。普通超时、连接被拒绝、权限失败或缺少应用 API 均不证明需要 Root，报告失败并允许用户重试。
3. 只有明确的协议不支持错误才允许一次自动已有 Root 桥接回退；此时先精确匹配系统适配，再检测已有 Root。无适配、Root 拒绝/未知或运行时验证失败时明确报告原因。自动流程从不执行临时 Root。
4. “准备兼容桥接”是独立操作，可供直连仍缺少兼容功能时使用。临时 Root 仍需用户单独确认；已有 Root、临时 Root 与 Bluetooth 适配各自判断，不根据品牌扩大能力。
5. 桥接成功后重新建立控制会话。停止或旧任务完成时，只有本次实际启动且运行实例仍吻合的桥接可被自动清理；复用的既有驻留不会被自动当成新建实例清除。

界面显示实际连接阶段和缺少的能力。控制通道就绪不代表音频所有权、双向切换、通话或实际出声已验收；缺少媒体状态权限时仍可使用耳机控制，但不能宣称自动交接可用。

## 增加桥接适配

`compatibility/adapters.json` 每项对应一个系统模块配置：稳定 ID、绝对模块路径、源码树内的 profile 路径。`match: "exact"`（默认）还必须提供文件 SHA-256 和 BuildID，作为唯一身份；`match: "prologue"` 可以省略这两个字段，只在 exact 适配未命中后作为回退尝试，且同一模块路径最多一个。profile 包含该版本实际验证的函数入口和结构偏移。

新增适配时：

1. 增加对应 profile 源文件，记录可信来源和实测范围；不得猜测偏移或把一个固件的偏移套到其他版本。
2. 在注册表增加唯一 ID。exact 适配可以有多个固件使用同一模块路径，但 SHA-256/BuildID 必须分别精确匹配；prologue 适配不写 SHA-256/BuildID，同一模块路径最多一个。重复身份及同时匹配多个候选会被拒绝。
3. 运行 `python tools/project.py check` 和 `python tools/project.py test`。构建工具会校验注册表与 profile 的身份一致性，并将所有配置分别打包到 `assets/root/adapters/<id>/`。
4. 运行时先读取模块实际 SHA-256 及 profile 指定位置的 BuildID 匹配 exact 适配；没有命中时才尝试 prologue 适配。既无 exact 命中、也没有可用 prologue 配置时返回无适配，不采用第一项或按 Android 大版本推测。
5. Root 准备只消费选中的适配。启用后的状态必须再次吻合目标耳机、profile 哈希和实际运行状态；APK 资产文件名使用标准正斜杠并验证原始 ZIP 条目。

## 动态解析适配器（实验性）

精确 SHA-256/BuildID 之外的同一模块可以有且只有一个 `"match": "prologue"` 配置。该配置不写 SHA-256/BuildID，profile 增加：

```json
"resolution": {"schema": 1, "mode": "prologue_scan"}
```

并给 `functions.l2cap/sdp` 提供在该模块中唯一的 prologue 字节。运行时先匹配精确配置；没有精确命中时才考虑 prologue 配置，且同一模块只能有一个。launcher 对这类配置不强制文件哈希，agent 在进程内扫描 prologue：

- 每个函数必须恰好一处命中，零处或多处都失败，不写内存、不安装 hook；
- 命中后仍校验函数入口字节；hook 触发的 PSM、目标 MAC、preferred mode 校验保持原样，任一不符不重写；
- 扫描模式下不写 SDP DI 身份：`sdp_identity` 偏移只适用于参考布局，不会随 prologue 扫描重定位，因此 agent 在非精确解析时关闭 DI 写入，状态顶层返回 `di_identity: false`；该能力在 probe 阶段确定，初始化、就绪与终止快照都不读取未验证的参考布局；
- 扫描模式还要求 `layout_fingerprint`：L2CAP 函数前 1024 字节必须与参考构建一致。CCB/RCB/LCB 等字段偏移编码在该函数代码中，指纹不匹配时在安装 hook 前失败，不会用参考偏移改写字段；
- 结构偏移（`offsets`、`sdp_identity`）仍来自 profile，不从代码猜测，版本差异必须另行验证。

prologue 长度必须在真实模块上验证唯一性：本机 `libbluetooth_jni.so` 的 32 字节 l2cap 前缀有 44 处重复，扩到 40 字节才唯一；sdp 的 32 字节前缀唯一。新增模块时先做离线唯一性检查，再上机验证。

这是实验性扩展，不等于支持范围：它只覆盖“函数代码未变、地址或 BuildID 变了”的同源模块；DI 身份与 SDP 布局不在扫描范围内，需要精确 profile。结构布局不同的模块仍需一套精确 profile。

临时 Root 的机型/固件精确白名单仍单独位于 `compatibility/targets.json`；增加 Bluetooth 适配不会增加漏洞适用范围。

当前注册表只有一套真实 SM-X810 配置。主机测试中的第二套配置为模拟数据，用于证明扩展、选择与拒绝逻辑；不能计为支持设备。跨设备和干净重启环境的设备验收仍待进行。
