# 变更日志

## [Unreleased] — 1.0.0-alpha 全量重建

**破坏性变更**：从零重写，**不兼容** 0.1.x 的数据目录与插件。

- 旧实现（`jellyfish-api` / `-core` / `-infra` / `-tui` / `-server` / `-cli` / `-di`）全部移除，
  保留在 tag `v0.1.2-final`。
- 新架构：领域层 / 应用层 / 基础设施层 / 装配层，模块为
  `jellyfish-domain`、`-application`、`-infrastructure`、`-shell`、`-di`、`-boot`。
- 插件体系移除：工具来源只剩 `INTERNAL` / `NATIVE` / `REST` / `MCP`；`Jellyfish-Plugins` 停止维护。
- 本期只做 TUI 外壳，不做 CLI / Server。
- 数据目录改为 `~/.jellyfish/`（文件系统持久化，子会话在 `sessions/<root>/sub/<child>/`）。
- 设计与技术决策见 `Jellyfish-Docs/model/`（7 份领域文档 + `tech/技术决策.md`）。

## [0.1.2] — 上一稳定版本

见 tag `v0.1.2-final`。
