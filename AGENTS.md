# AGENTS.md

本文件用于指导 AI 编码代理在本仓库中工作。**修改代码前请先读本文件**。

> 定位：只放「每次动手前都该知道的规则」——项目描述、模块边界、关键不变量、编码 / 测试 / Git 约定。
> 设计细节不在本文件：领域模型与技术决策在 `Jellyfish-Docs/model/`（见下）。

## 项目描述

Jellyfish 是一个用 **Java 1.8** 编写的**本地终端 AI Agent**：你给它一句话，它自己判断要不要拆任务、
调工具、必要时反问你，并把踩过的坑记下来。**本期只实现 TUI 外壳**（`jellyfish-shell`），不做 CLI / Server。

- **三层 + 装配**：领域层（规则）/ 应用层（编排、事务、端口）/ 基础设施层（文件、模型、终端）/ 装配层（Dagger）。
- **一切皆工具**：`generate_plan` / `submit_step` / `ask_user` / `spawn_subagents` / `load_skill` 与"读文件""跑命令"
  没有本质区别，模型自己决定调哪个。
- **文件即数据库**：数据在 `~/.jellyfish/`，无数据库、无守护进程。
- **插件体系已移除**：旧 PF4J 插件随 1.0.0-alpha 全量重建一并删除（技术决策 D01.1），工具来源只有
  `INTERNAL` / `NATIVE` / `REST` / `MCP`。

## 设计基准（唯一出处）

| 文档 | 位置 | 作用 |
|---|---|---|
| 领域模型（静态站） | `../../Jellyfish-Docs/model/docs/`：`index.html` 入口；`03-overview` / `04-perception` / `05-action` / `06-planning` / `07-memory` / `08-application` / `09-infrastructure` | 是什么、为什么（**唯一依据**） |
| 1 份技术决策 | `../../Jellyfish-Docs/model/tech/技术决策.md`（D01–D16） | 怎么落、选了什么 |
| 1 份改动记录 | `../../Jellyfish-Docs/model/实现过程的模型改动记录.md` | 模型/决策的每次改动与代码落点 |

**代码是事实，文档是解释**：实现与文档冲突时，只有两个合法动作——改代码，或改文档并注明原因。

## 工作原则（最高优先级）

1. **唯一依据**：只以 `Jellyfish-Docs/model/docs/` 的 HTML 文档站（以及 `model/tech/技术决策.md`）为准。
   历史实现、网络资料、个人经验一律**不作为设计依据**，也不要引入文档里没有的概念。
2. **模型与代码强绑定**：代码里每条规则都要能指回文档小节；文档里每条规则都要有代码落点。
   实现一条规则时，在关键处写一行出处注释（`// 规则出处：06-planning §6.8.1 规则 5`），提交信息里注明对应小节。
   **找不到出处的实现，不要写**；先问。
3. **不擅自改方案**：不动 `Jellyfish-Docs/model/` 下任何文件。发现文档有缺口、矛盾或与实现冲突时，
   **停下来说明，等裁决**；方案变更的唯一路径是：先改文档站（经同意）→ 再改代码。
4. **代码即文档**：命名与结构要自解释；注释只写"为什么"（取舍、坑、非显然的约束），
   不写"做什么"或"怎么做"。能用类型与命名表达的，不要用注释。
5. **模型改动必须留痕**：任何对 `Jellyfish-Docs/model/` 的改动（新增 / 修改 / 删除模型元素、规则、
   端口契约、技术决策），都必须先在 `Jellyfish-Docs/model/实现过程的模型改动记录.md` 追加一条记录
   （编号、日期、触发切片、改了什么、涉及文件、代码落点），再改代码。
   **没进改动记录的模型改动，视为没发生**。

## 对账（每个切片结束时必跑）

```bash
python3 ../Jellyfish-Docs/tools/model-check.py .
```

检查“模型 ↔ 代码”两方一致：模型类 / 枚举常量 / 文档方法/HTML 图。模型文档只有 HTML 站，
没有 markdown 源，所以脚本不做 md ↔ html 镜像比对。**非零退出即停下修，不要继续往下写**（D15 纪律 1）。

## 模块与依赖方向

```
jellyfish-domain          ← 纯 JDK，无内部依赖
jellyfish-application     → domain            （用例、应用组件、端口定义）
jellyfish-infrastructure  → application        （实现端口）
jellyfish-shell           → application        （TUI，只调用例）
jellyfish-di              → application, infrastructure, shell   （Dagger 组件与 Provider）
jellyfish-boot            → di                 （读配置、创建组件、main）
```

**依赖方向单向，禁止反向或循环**。`jellyfish-di` 认识所有模块，**没有任何模块依赖它**——
这是"容器概念不渗入业务"的结构保障。

包名统一前缀 `io.github.zcd0831.jellyfish.*`；每个认知模块一个包，**一个聚合一个包**（聚合内部实体包内可见）。

## 关键不变量（违反了就是 bug）

1. **收敛 ≠ 完成**：模型不再调工具只代表本轮结束；算不算做成由验证链判定。
2. **提交 ≠ 完成**：`submit_step` 只是自我声明；验证通过才固化。
3. **事件只通知**：领域事件在提交后同步派发，**不得驱动任何 IO 长任务**；检索/沉淀由 `ExecutionLoop` 显式调用。
4. **提交顺序固定**：不可变事实 → 追加流水 → 替换聚合（由 `CommitScope` 强制）。
5. **绝不跨 IO 持事务**：模型调用、工具执行、`ask`、子代理 join 都在提交之外。
6. **环境故障不是业务失败**：模型不可用 / 请求超限**不改任何聚合状态**、不消耗重试。
7. **可推导状态不落库**：`retryCount`、上下文窗口、token 用量都不进文件。
8. **聚合不读配置**：阈值判定由调用方（外循环）做，聚合只保证状态迁移合法。
9. **单写者**：`InstanceLock` 用 `FileChannel.tryLock`；每个会话一个写者，子代理写 `sessions/<root>/sub/<child>/`。
10. **断流不落 Record**：半截输出不算事实。
11. **`Record` 只追加**：被 `Attempt.recordRange` 引用的区间永不删除/改写。

## 编码约定

- **只能用 Java 8**：禁止 `var`、`record`、`sealed`、文本块、`List.of`、`ProcessHandle`、虚拟线程等 9+ 特性。
- **Lombok**：仅允许 `domain` 的值对象用 `@Value`；禁止 `@Data`/`@Setter`/`@Builder`；应用层、基础设施层、外壳一律不用。
- **DI 注解只允许出现在 `jellyfish-di`**：一律 `@Module` + `@Provides` 显式 `new`；其它模块不得 import 容器类型。
- **应用层不出现** `File`、`Path`、`HttpClient`、`System.out`——一切经端口（由 ArchUnit 检查）。
- **错误表达**：领域不变量违反抛 `DomainInvariantViolation`（不捕获、冒到最外层写 error 日志 + 现场快照）；
  应用用例返回 `Result<T, UseCaseError>`；错误码按上下文拆枚举，code 前缀与包名对应（`P_`/`S_`/`C_`/`M_`/`T_`/`A_`/`B_`/`E_`/`X_`）。
- **用户文案不进领域/应用层**：`code → 文案` 的映射只在 `jellyfish-shell` 的资源文件里。
- **凭据只从环境变量读**：`ApiCredential.apiKeyRef` 是**环境变量名**；密钥不进日志、不进异常、不进配置。

## 测试约定

- JUnit 5；**不使用 Mockito**（假端口手写）；不追求覆盖率数字，盯"关键规则有断言"。
- 四层：领域单测（纯内存）→ 应用层假端口（`ScriptedModelInvocationPort`）→ 基础设施契约测试（9 个仓储全做）→ 无头端到端。
- **禁止在测试里调用真实模型**。
- 涉及文件系统的测试必须用 `--data-dir` 指向临时目录，并在 `@AfterEach` 删除。

## 常用命令

```bash
mvn -q compile                      # 编译
mvn -q test                         # 全量单测
mvn -q -Dtest=XxxTest test          # 单类单测
mvn -q package -DskipTests          # 打包
mvn -q validate                     # 只校验 POM（改模块结构后先跑这个）
```

## Git 约定

- 旧实现保留在 tag **`v0.1.2-final`**，可随时 `git show v0.1.2-final:path` 查阅，**不要**做 history rewrite。
- 提交信息用中文，前缀用 `feat` / `fix` / `docs` / `test` / `chore` / `refactor`，可带作用域，如 `feat(domain): …`。
- 一个提交只做一件事；结构重构与行为变更分开提交。
