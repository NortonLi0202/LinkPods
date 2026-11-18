# 议题追踪

工程任务与规格使用 `NortonLi0202/LinkPods` 的 GitHub Issues。版本已知问题和真机验收记录见 `docs/issues/README.md`。

- 读取任务用 `gh issue view`，筛选任务用 `gh issue list`。
- 获得当前任务的发布授权后，用 `gh issue create` 创建议题。多行正文通过 `--body-file` 传入。首次写入前用 `gh repo view --json nameWithOwner` 核对目标仓库。
- 子任务使用 GitHub 子议题，阻塞关系使用 GitHub 原生依赖。功能不可用时，在子任务正文顶部用 `Part of #编号` 和 `Blocked by: #编号` 记录相应关系。
- 发布评论、调整标签和关闭议题分别使用 `gh issue comment`、`gh issue edit` 和 `gh issue close`。关闭前记录处理结果。

**PRs as a request surface: no.**

## Wayfinder

用带 `wayfinder:map` 标签的议题记录工作地图。子任务使用 `wayfinder:<type>` 标签，类型为 `research`、`prototype`、`grilling` 或 `task`。按地图顺序领取首个未分配且无未关闭阻塞项的子任务。完成后在子任务中记录结果并关闭，再将结论链接补入地图。
