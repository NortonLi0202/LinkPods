# 兼容范围

LinkPods v1.0 的安装下限是 Android 12L/API 32，当前打包 ARM64。安装成功、已有 Root 和实际支持自动交接是不同条件；当前没有完成稳定性验收的生产支持设备。

- 直连优先：先尝试真实 AACP 握手，成功则不请求 Root；普通超时、连接拒绝或权限问题不会触发自动 Root 回退。已成功的直连断开后继续直接重连。
- 需要桥接：仅明确的协议兼容错误触发一次自动已有 Root 回退，也可由用户单独选择“准备兼容桥接”。先匹配适配器，再验证 UID 0；拒绝或未知时不绕过用户授权。
- 无 Root：只有 compatibility/targets.json 中精确匹配的机型、固件、内核及 Bluetooth 模块才提供临时 Root 流程。目前仅有 SM-X810 的一套精确配置，不是全三星或全 Android 支持。
- 原生桥接：按 adapters.json 中的模块路径、SHA-256 和 BuildID 匹配，不按系统大版本猜偏移。存在 Root 不表示有可用桥接适配。`match: prologue` 的实验性配置在精确配置不命中时按函数 prologue 唯一扫描，结构偏移仍来自 profile；扫描不唯一或运行时校验失败时不安装 hook。
- adapters.json 是实际打包和运行时选择的输入。所有注册配置按 ID 独立打包；匹配不唯一时拒绝选择，不默认使用第一项。增加配置步骤见 [适配说明](../docs/adapters.md)。
- 音频：双向切换、双直播、锁屏和通话需要各自实测；历史成功片段不能替代当前版本验收。

主机能力分类工具仅分析调用方提供的观测：

```powershell
python tools/compatibility.py compatibility/example-observations.json
```

此命令不访问设备，不执行 ADB、su、注入或漏洞。targets.json 表示打包的适配身份，不代表 Root 成功或实际交接通过。当前源码仅保留适配条件，不保存个人设备试验历史。
