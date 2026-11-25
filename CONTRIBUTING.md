# 构建与维护

## 工具

Windows、Python3.10+、Git、JDK17、Node.js；APK 构建另需 Android SDK platform android-36、build-tools36.0.0、NDK28.0.13004108。不需要 Linux 虚拟机。

```powershell
$env:JAVA_HOME = '<JDK17目录>'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
python tools/project.py check
python tools/project.py test
```

`check` 校验源文件、第三方来源清单和 Git 收录范围。缺少未上传的二进制依赖时会明确报告源码模式；若本地已经有依赖，则哈希错误仍会失败。`--with-local-inputs` 另外要求全部依赖已提供。主机测试不是实际耳机功能验收。

## 本地依赖

1. 按 third_party/payloads/manifest.json 准备四个精确版本输入。它们包括原 DFRoot APK、loader、适配模块和 Frida inject；不存在通用 root 二进制，无法保证别的固件适用。
2. 按 third_party/dependencies.json 下载 HiddenApiBypass6.2 的官方 AAR，校验源 AAR 哈希，将其中 classes.jar 放到 apps/android/lib/hiddenapibypass-6.2.jar，再校验 JAR 哈希。
3. 不把这些 APK/JAR/原生二进制加入 Git。构建不会隐式下载或接受哈希不符的替代文件。

third_party/sources 保留 APK 编译需要的源文件和模块对应补丁及上游提交。当前 APK 脚本不从头编译上游内核或生成适配模块；仍需清单中的本地模块输入。不要把对应源码记录误当成已提供完整独立内核构建环境。

## 签名

使用自己的签名身份，并在本地 `.local/signing/release.private.json` 配置以下字段；示例值必须替换：

```json
{
  "keyStore": ".local/signing/your-release.jks",
  "alias": "your-alias",
  "storePassword": "<本地密码>",
  "keyPassword": "<本地密码>"
}
```

配置和私钥只留本地，必须一起备份。更新已安装应用需要相同证书；不要为规避签名冲突擅自卸载或清除用户数据。缺少发布配置时构建失败，不自动生成测试密钥。

## 构建

```powershell
python tools/project.py check --with-local-inputs
& .\apps\android\build.ps1 -SdkPath '<Android SDK目录>'
```

输出 apps/android/build/release/LinkPods-v1.1.apk：lp.pods、1.1/code102、minSdk32、ARM64、debuggable=false。构建包含交叉编译、签名和载荷验证，不安装或运行 Root。Windows aapt2 使用临时目录输出 .flat 后链接，避免该资源集的 zip 输出崩溃。

图片及控件参考来源见 apps/android/UI_ASSET_NOTICES.md，通知随 APK 打包。原生高光和回弹是近似实现，不包含真实背景折射。

## 开发和归档

新任务从 main 派生 `<类型>/<英文 kebab-case>` 分支。提交前运行相应主机测试，不把编译成功当作耳机有声。不要提交私钥、凭据、原始设备日志、固件、个人设备标识或生成的安装包。

`python tools/project.py clean` 默认预览受管 build 输出；加 `--apply` 才删除。含密钥或链接的子项受保护。`snapshot` 用唯一 ID 归档应用源码和 APK，拒绝覆盖；本地 releases 不加入 Git。

## 提交 Issue

提交 Bug 报告或功能建议时，请尽量提供以下信息：

- **问题描述 / 功能目标**：说明预期行为与实际行为（或使用场景）
- **环境信息**：LinkPods 版本（含 build 号）、设备型号、Android / One UI 版本、AirPods 型号
- **运行路径**：直连 / 已有 Root 桥接 / 临时 Root（若涉及）
- **复现步骤**：按顺序给出最小可复现步骤，并附上脱敏后的关键日志或截图（不要上传含个人敏感标识的原始设备数据）

## 提交 Pull Request

1. 从 `main` 派生 `<类型>/<英文 kebab-case>` 分支，每个 PR 聚焦一类变更。
2. 同步维护相关文档：行为变更更新 `SPEC.md`，模块/边界变化更新 `ARCHITECTURE.md`，术语变化更新 `GLOSSARY.md`，变更记录更新 `CHANGELOG.md`。
3. 在 PR 说明中写清：解决的问题（关联 Issue，如 `Fixes #123`）、最终行为、已执行的验证命令及结果（至少包含 `python tools/project.py check` 与相关 `test`），并**明确列出未执行的检查**（例如真机听感、双向交接或跨设备验收）。
4. 审核回复统一遵循 [docs/reviews/PR-REVIEW-TEMPLATE.md](docs/reviews/PR-REVIEW-TEMPLATE.md)；审核过程中的日志、截图与轮次报告留在 PR 评论中，不写入 `docs/reviews/`。
