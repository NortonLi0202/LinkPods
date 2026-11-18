# 维护约定

- 当前版本为 LinkPods1.1/build102 预发布。先读 README、project.json、docs/release/v1.1.md 与 docs/issues/README.md。
- 新工作建 `<类型>/<英文 kebab-case>` 分支，不直接在 main 开发。只推送本次任务明确授权的分支，不推送本地研究分支或旧历史。
- 已有 Root 优先；无 Root 时仅使用精确匹配的运行时适配。禁止刷机、刷 bootloader、解锁和永久 Root。
- 普通连接先直连；不要预先检测 Root。桥接使用 compatibility/adapters.json 精确匹配，不能默认第一项；普通超时/权限失败不能当成缺少 Root 的证据。
- 禁止刷写、擦除块设备，禁止改变分区只读标志或重挂系统分区。允许正常安装及 userdata 应用文件写入；不得把临时 Root 描述为完全不落盘。
- 设备操作需要当前任务明确授权；源代码修改、构建或发布不自动授权重启、漏洞、注入、配对或启动交接。
- 区分源码、构建、部署和耳机实际听感；未测试的功能不得标记为稳定或扩展支持范围。
- 私钥、密码、APK/JAR/原生二进制、固件和原始设备数据不入 Git。UI PNG 是构建资源，保留必要来源与许可通知。
- 不删除唯一源码、证据或签名身份，不 reset 上游研究工作树。当前构建使用 third_party/sources，不依赖旧研究目录。
- 常规变更运行 python tools/project.py check；修改行为运行相关主机测试。完整构建先检查 --with-local-inputs。
- 不虚构许可、CI、公开发布、维护者和验收结果；不得擅自对外发布或发送消息。

## Agent skills

### Issue tracker

在 GitHub Issues 读取、创建或更新任务时，参考 `docs/agents/issue-tracker.md`。

### Triage labels

为议题分诊或按标签筛选任务时，使用 `docs/agents/triage-labels.md` 中的默认标签映射。

### Domain docs

本仓库采用单上下文布局。探索领域术语或架构决策前，参考 `docs/agents/domain.md`。
