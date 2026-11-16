# 临时 Root payload：移植与导入指南

> 结论先说：**当前版本还不支持普通用户在 App 里直接导入自己的 payload。**
> 现有构建只打包一套精确 payload，`compatibility/payloads.json` 负责“精确选择或拒绝”，不会执行用户放进来的文件。
> 下面这份教程面向**移植者/开发者**：把一台新设备的 payload 做进仓库并构建。普通用户导入需要额外的签名包和导入 UI，见最后一节的设计草案。

## 安全边界

临时 Root payload 是**在内核里加载模块、以 root 等价权限运行**的代码。导入或运行错误的 payload 可能导致：

- 蓝牙进程崩溃、系统重启、卡死；
- 内核 panic 或需要强制重启；
- 数据丢失；
- Knox/完整性状态变化（即使不刷 bootloader）。

只在你拥有或明确获授权的设备上操作；不要分发来源不明的 payload；不要把它描述成“无风险”。

## 一套 payload 包含什么

| 构件 | 作用 | 是否按设备定制 |
|---|---|---|
| `libdfroot.so` | 漏洞利用，加载内核模块 | **是**，按内核/固件 |
| `diagnostic.ko` | KernelSU 诊断/加载模块 | **是**，按内核构建 |
| `libksud.so` | KSU late-load 加载器 | **是**，按内核/ABI |
| `liblpbootstrap.so` | root broker，负责和 App 通信 | 通常复用 LinkPods 构建 |

蓝牙桥接用的 `frida-inject` 由 LinkPods 自己打包，不属于 payload。

构建不会直接读取独立的 exploit ELF。需要把包含 `lib/arm64-v8a/libdfroot.so` 的 APK 放入 `third_party/payloads/`，并在 `third_party/payloads/manifest.json` 的 `original_apk` 中固定文件名和 SHA-256；当前默认文件是 `DFRoot_4.7.apk`。exploit 不满足构建假定的内部布局时，需要同步调整构建流程。

## 1. 采集精确设备身份

在目标设备上执行：

```sh
getprop ro.product.model
getprop ro.build.version.sdk
getprop ro.build.version.incremental
uname -r
sha256sum <compatibility/adapters.json 中目标适配器的 module_path>
```

`compatibility/payloads.json` 的 `target` 必须和这五项**完全一致**：

```json
"target": {
  "model": "SM-X810",
  "android_sdk": 36,
  "firmware": "X810ZCS6EZH3",
  "kernel": "5.15.189-android13-8-3251856-abX810ZCS6EZH3",
  "bluetooth_sha256": "4dc06d067a9a237f3a882ba3a6daabe469a8c0deb06731579d254c37a10cefa8"
}
```

注意内核要写 `uname -r` 的**完整**字符串，包括 `-ab...` 后缀；少一段就会永远匹配失败。

## 2. 找到或移植 payload

现有可参考的来源：

- **DFRoot**（diabl0w）：本项目当前使用的 exploit 来源；
- **Root My Galaxy**（BuSung-dev）：按固件打包的 CVE-2026-43499 payload，含 KernelSU late-load；其 `docs/PORTING.md` 记录了固件到 profile 的完整流程。

规则：

1. 只使用**同型号、同固件、同内核**的现成 profile；
2. 不要把 A 固件的偏移套到 B 固件；
3. 不要绕过 Root My Galaxy 的授权/许可要求；
4. 没有对应 payload 时，需要自己做内核符号恢复、偏移验证、模块编译——这是研究/逆向工作，不是改配置。

## 3. 加进仓库并构建

当前构建**只支持一套 payload**。步骤：

1. 把构件放入 `third_party/payloads/`，在 `third_party/payloads/manifest.json` 固定文件名、SHA-256 和字节数；
2. 在 `compatibility/payloads.json` 增加或替换条目：
   ```json
   {
     "id": "sm-x810-x810zcs6ezh3-dfroot",
     "status": "unverified",
     "target": { "...": "五项精确身份" },
     "artifacts": {
       "exploit": "libdfroot.so",
       "module": "diagnostic.ko",
       "loader": "libksud.so",
       "bootstrap": "liblpbootstrap.so"
     }
   }
   ```
完成真机验证前保持 `unverified`；验证通过后再改为 `device_tested`。

3. 同步 `compatibility/targets.json` 的临时 Root 目标（当前构建要求两者一致）；同时在 `compatibility/adapters.json` 增加与该机蓝牙模块匹配的适配器及 profile：`module_path` 必须和上面计算的路径一致，`sha256`/BuildID 或 prologue profile 的参考哈希必须对应目标模块。缺少匹配适配器时 `prepare()` 会报 `temporary Root target requires one exact module path`；
4. 运行：
   ```powershell
   python tools/project.py check
   python tools/project.py test
   powershell -File apps/android/build.ps1
   ```
5. 用 `standalone_payload.py --verify-apk` 或构建脚本确认 APK 内的目录、构件哈希和身份一致。

主机侧校验会拒绝：重复身份、缺字段、非法构件名、状态不在枚举内、目录与 targets 不一致。

## 4. 真机验证清单

不要在“能安装”就宣布支持。至少确认：

- [ ] 漏洞执行后模块为 `Live`；
- [ ] `uid=0` 且安全上下文正确（不是只拿到半截 root）；
- [ ] `ksud` late-load 成功，没有 SELinux 上下文错误；
- [ ] 重启后模块消失，再次准备可以重建；
- [ ] 蓝牙桥接能安装 hook，状态 `enabled`；
- [ ] 停止后能确认 `disabled` 清理；
- [ ] 实际出声、双向切换、锁屏、来电各自试听；
- [ ] 失败路径不会反复执行漏洞或残留半初始化状态。

## 5. 普通用户导入：设计草案（尚未实现）

如果要做“用户自己导入”，建议的最小安全模型：

1. **签名包**：zip 内包含 `payload.json`（身份、构件 SHA-256、打包者、版本）和构件，附 ed25519/minisign 签名；
2. **精确匹配**：App 读取本机五项身份，必须唯一命中；
3. **哈希校验**：逐构件校验 SHA-256，签名验证失败直接拒绝；
4. **无网络**：只接受用户本地选择的文件，不自动下载；
5. **二次确认**：明确写出“root 等价代码、可能重启/卡死/丢数据”，用户手动确认；
6. **开发者模式**：未签名包只在显式开发者选项下允许，生产默认拒绝。

这些都需要额外的导入 UI、签名信任根和 staging 执行支持，当前代码没有实现；仅靠一份教程不能让任意用户安全导入。

## 常见错误

- 内核字符串少了 `-ab...` 后缀：精确匹配永远失败；
- 只换 `payloads.json` 不换构件：构建会拒绝，或运行错误固件的漏洞；
- 把 prologue 适配当成 payload 适配：前者只解决蓝牙函数地址，后者解决内核提权，二者独立；
- 在未验证的设备上反复执行漏洞：可能 panic/卡死，且不会因为重试变得兼容。
