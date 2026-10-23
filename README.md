# Jellyfish

用 **Java 1.8** 编写的本地终端 AI Agent：领域分层、显式端口、文件系统持久化。

> **状态：1.0.0-alpha 全量重建中。**
> 旧的 0.1.2 实现（含 PF4J 插件体系与三种外壳）保留在 tag [`v0.1.2-final`](../../tree/v0.1.2-final)，
> 新实现为从零重写，**不兼容旧数据与旧插件**。

## 设计文档

设计与技术决策不在本仓库维护，在 `Jellyfish-Docs` 仓库：

| 内容 | 位置 |
|---|---|
| 7 份领域模型文档 | `Jellyfish-Docs/model/`（`模型图.md` + 四份模块文档 + 应用层 + 基础设施层） |
| 技术决策（D01–D15） | `Jellyfish-Docs/model/tech/技术决策.md` |
| 可读版（HTML + 图） | 打开 `Jellyfish-Docs/model/docs/index.html` |

## 模块

| 模块 | 职责 |
|---|---|
| `jellyfish-domain` | 聚合、领域服务、领域策略（纯 JDK） |
| `jellyfish-application` | 用例、应用组件、21 个端口定义 |
| `jellyfish-infrastructure` | 端口实现：文件、模型、工具、终端 |
| `jellyfish-shell` | TUI 外壳（本期唯一外壳） |
| `jellyfish-di` | Dagger 装配（唯一允许出现 DI 注解的模块） |
| `jellyfish-boot` | 配置解析、装配、`main` |

## 构建

```bash
mvn -q compile
mvn -q test
mvn -q package -DskipTests
```

运行环境 **JDK 1.8**（不要使用 Java 9+ 的 API 或语法）。

## 数据目录

默认 `~/.jellyfish/`，可用 `--data-dir` 指定；日志级别用 `--log-level trace|debug|info|warn|error`。

## 约定

编码、测试与 Git 约定见 [`AGENTS.md`](AGENTS.md)。
