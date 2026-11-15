# 兼容范围

LinkPods v1.0 的安装下限是 Android 12L/API 32，当前打包 ARM64。安装成功、已有 Root 和实际支持自动交接是不同条件；当前没有完成稳定性验收的生产支持设备。

- 已有 Root：优先通过 su 请求授权并验证 UID 0；拒绝或未知时不绕过用户授权。
- 无 Root：只有 compatibility/targets.json 中精确匹配的机型、固件、内核及 Bluetooth 模块才提供临时 Root 流程。目前仅有 SM-X810 的一套精确配置，不是全三星或全 Android 支持。
- 原生桥接：按 adapters.json 中的模块路径、SHA-256 和 BuildID 匹配，不按系统大版本猜偏移。存在 Root 不表示有可用桥接适配。
- 音频：双向切换、双直播、锁屏和通话需要各自实测；历史成功片段不能替代当前版本验收。

主机能力分类工具仅分析调用方提供的观测：

```powershell
python tools/compatibility.py compatibility/example-observations.json
```

此命令不访问设备，不执行 ADB、su、注入或漏洞。targets.json 表示打包的适配身份，不代表 Root 成功或实际交接通过。当前源码仅保留适配条件，不保存个人设备试验历史。
