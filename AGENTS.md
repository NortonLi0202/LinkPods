# 维护约定

- 当前源码基线为 LinkPods1.0/build101。先读 README、project.json、docs/release/v1.0.md 与 docs/issues/README.md。
- 新工作建 `<类型>/<英文 kebab-case>` 分支，不直接在 main 开发。只推送本次任务明确授权的分支，不推送本地研究分支或旧历史。
- 已有 Root 优先；无 Root 时仅使用精确匹配的运行时适配。禁止刷机、刷 bootloader、解锁和永久 Root。
- 设备操作需要当前任务明确授权；源代码修改、构建或发布不自动授权重启、漏洞、注入、配对或启动交接。
- 区分源码、构建、部署和耳机实际听感；未测试的功能不得标记为稳定或扩展支持范围。
- 私钥、密码、APK/JAR/原生二进制、固件和原始设备数据不入 Git。UI PNG 是构建资源，保留必要来源与许可通知。
- 不删除唯一源码、证据或签名身份，不 reset 上游研究工作树。当前构建使用 third_party/sources，不依赖旧研究目录。
- 常规变更运行 python tools/project.py check；修改行为运行相关主机测试。完整构建先检查 --with-local-inputs。
- 不虚构许可、CI、公开发布、维护者和验收结果；不得擅自对外发布或发送消息。
