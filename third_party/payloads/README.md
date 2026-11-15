# 固定本地输入

manifest.json 保存独立 APK 所需输入名称、SHA-256 和来源。这里不上传二进制；克隆后需按清单单独准备。构建不自动下载，缺失或哈希不符会失败。

- DFRoot_4.7.apk：DFRoot 上游 v4.7 APK，构建时仅重定位八处等长路径。
- libksud.so：来自所列 KernelSU APK 的 loader，仅用于显式候选加载，不执行 userspace late-load。
- diagnostic.ko：清单固定的适配模块，当前 APK 脚本不编译模块；对应修改和上游提交在 third_party/sources/kernelsu。
- frida-inject：Android ARM64 17.26.3；官方压缩包 URL 和压缩/解压哈希均在清单中。

HiddenApiBypass 的 JAR 是另一项本地输入，见 third_party/dependencies.json。它的许可文本保留在 apps/android/lib。

许可证与未完成的分发材料审查见根目录 THIRD_PARTY.md。不要在此目录放签名身份、固件、VM 或设备日志。
