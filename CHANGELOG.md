## [1.0.0-alpha] - 进行中

### S0 骨架
- 6 模块（domain / application / infrastructure / shell / di / boot）与单向依赖；Dagger 装配
- `domain.shared`：13 个 Id、`DomainError`、`DomainInvariantViolation`
- 21 个端口接口；`Result` / `UseCaseError` / `CancellationToken` / `ChunkSink` / `ProgressEvent`
- 文件适配器：`FileCommitScope`（事实→追加→替换）、`FileSessionRepository`、`FileRecordStore`、
  `FileInstanceLock`、`FileSchemaMigrator`、`JsonCodec`
- 验收：建会话 → 写 meta.json → 追加 record → 重新加载读回

### S1 无计划对话
- 会话窗口推导（`buildContextForLlm`：SYSTEM 注入不淘汰、只取最新 SUMMARY、超占比整段降级）
  + 上下文折叠（超限才摘要，摘要提示词不进 Record）
- `ModelInvoker`：OpenAI 兼容 `/chat/completions`（D16）、同模型 3 次退避重试（500/1500/4000 ±20%）、
  `Retry-After > 60s` 直接降级、备用链逐个重新硬校验、凭据只从环境变量读
- 协议映射：`systemPrompt` → 首条 system、`ChatMessage` ↔ `messages`、`ToolCall` ↔ `tool_calls`、
  `usage` ↔ `ModelUsage`；流式 `StreamAccumulator` 支持工具参数分片
- 工具：`ask_user`（内置）+ REST；完整 JSON Schema（draft-07）校验；非只读且未可信 ⇒ 放行审批；
  超长截断并归档全文
- 记账：`BudgetService` 归集到会话族根，`budget.json` 累加，首次越 `budgetLimitTokens × budgetWarnRatio` 预警
- 用例：`StartSession`（冻结快照、建台账、标题降级）、`RunSession`（T1、取消是正常结果、模型不可用不改状态）
- 外壳：TUI 读一行 → 流式渲染；错误码 → `messages.properties` 文案
- 验收（`S1AcceptanceTest`）：一句话 → 一次 `ask_user` → 最终 `ASSISTANT` 落盘、token 进台账、
  请求携带冻结的工具快照

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
