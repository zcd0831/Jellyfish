# 约束：权限与审批

> 本文是 [AGENTS.md](../../AGENTS.md) 的 L1 分域约束：**改权限判定、审批通道、只读白名单前必读**。
> 面向使用者的行为说明见 [README.md](../../README.md) 与 [configuration.md](../configuration.md) 的「工具权限」；
> 背景见 [architecture.md](../architecture.md)。只写规则与事实；推导与实测数据在对应类的注释里。

## 权限三层

**核心策略 → PLAN 只读白名单 → 插件拦截**，再统一处理 ASK 与审计。

- **fail-open 只覆盖「取不到策略」**；策略一旦生效，它的否定就是硬结论。

## 插件拦截：三态，取最严

- **`PermissionVerdict` 是三态（`ABSTAIN` / `ASK` / `DENY`）**，合并**取最严**
  （`DENY > ASK > ABSTAIN`）且 **`DENY` 短路**。
- **同为 `ASK` 时保留先到者的理由**（覆盖它只会让审计里的理由随插件顺序变化）。
- **插件抛错按 `ABSTAIN` 处理**。
- **`PermissionVerdict` 里没有 `ALLOW`，因此「插件不能放宽核心策略」是编译期约束**；
  `ASK` 只可能让调用更严——它最终仍走 `ApprovalChannel`，拿不到批准就降级为拒绝。
- **需要 `ASK` 的理由**：只有两态时「只读命令免打扰、写类命令要人看一眼」根本写不出来，用户只剩「全放行」与
  「每次都点批准」两个选择，而后者最终会退化成前者。

## 审批：fail-closed

- **ASK 由 `ApprovalChannel` 收口，只有明确批准才放行**：**无审批者、超时、溢出、通道关闭、中断一律拒绝**。
- 超时来自 `permission.approvalTimeoutSeconds`（缺省 120，**每轮现读**）。
- **`Esc` 是「拒绝 + 中断回合」**，不是只拒绝。

## 只读白名单

**只读白名单 = `ToolDescriptor.readOnly`（提供方声明，随 handler 落表）∪ `plugins.configurations.<pluginId>.readOnlyTools`
（用户只能追加）**，由 `ReadOnlyTools` 现算。

- 插件无法自称某个写操作是只读的——这一条由「用户只能追加」保证，不是靠约定。

## 三种外壳下的审批

| 外壳 | 行为 |
| --- | --- |
| `-tui` | 弹出审批选择框（`↑`/`↓` 选，`Enter` 确认，`Esc` 拒绝并中断回合），批准才执行 |
| `-server` | 推 `approval_required` SSE 事件，由 `POST /approvals/{requestId}` 裁决 |
| `-cli` | 没有审批界面（也没有审批者），**一律按拒绝处理**——绝不静默放行 |

- **`ApprovalChannel` 是全局单槽位**：任一时刻最多只有一条待审批项，多会话并发时后面的会排队。
  这是既有语义，**首轮明确不改内核**，如实暴露现状。

## 与工具清单过滤的关系

`ToolFilter`（子代理工具清单收窄）的判据**不重写，而是复用执行期判定**：
`PermissionManager.usableTools(agentId, mode)` 内部就是 `!evaluatePolicy(...).isDenied()`。

两处各写一遍「显式拒绝 > 需审批 > 允许收窄 > PLAN 白名单」，**迟早会在某个边界上分叉**。两个推论：

- **`ASK` 不算被拒**（那个工具可用，只是要点一下批准）。
- **插件拦截不参与过滤**（它要看参数、可能问人，是「本次调用」才能回答的问题）。
