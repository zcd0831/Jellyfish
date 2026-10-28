# AGENTS.md

本文件用于指导 AI 编码代理在本仓库中工作。**修改代码前请先读本文件**。

> 定位：只放「每次动手前都该知道的规则」——项目描述、模块边界、关键不变量、编码 / 测试 / Git 约定。

## 项目描述

Jellyfish 是一个用 **Java 1.8** 编写的**本地终端 AI Agent**：你给它一句话，它自己判断要不要拆任务、
调工具、必要时反问你，并把踩过的坑记下来。

- **三层 + 装配**：领域层（规则）/ 应用层（编排、事务、端口）/ 基础设施层（文件、模型、终端）/ 装配层（Dagger）。
- **一切皆工具**：`generate_plan` / `submit_step` / `ask_user` / `spawn_subagents` / `load_skill` 与"读文件""跑命令"
  没有本质区别，模型自己决定调哪个。
- **文件即数据库**：数据在 `~/.jellyfish/`，无数据库、无守护进程。

## 工作原则

1. **代码即文档**：命名与结构要自解释；注释只写"为什么"（取舍、坑、非显然的约束），
   不写"做什么"或"怎么做"。能用类型与命名表达的，不要用注释。

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

- 使用 JUnit 5 框架进行单测；使用 Mockito 框架进行 Mock；使用 Jacoco 计算单测覆盖率。
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
