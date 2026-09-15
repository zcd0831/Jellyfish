# TUI 外壳落地方案（`-tui` 交互式终端界面）

> 状态：**口径已全部裁决**（T1～T8，见 §10 裁决记录），分两步落地：**T2.1 骨架** → **T2.2 流式与细节**。
> 范围：抽 `jellyfish-tui` 模块、TamboUI 接线、声明式界面、TUI 版 `ReActListener`、线程契约、键位与焦点、滚动跟随、日志隔离、打包、单测、文档同步。
> **不动** `jellyfish-api` / `jellyfish-infra` / `jellyfish-core` 的任何生产代码——`-tui` 所需的接缝（`AgentHarness.chat` / `CommandManager` / `SessionManager` / `ReActTurn.cancel`）**已全部就绪**，本轮不需要内核新增任何能力。
> 顺序（已裁决）：**CLI ✅ → TUI（本轮）→ Server**。

---

## 0. 已确认口径（用户裁决）

| 编号 | 议题 | 裁决 | 备注 |
| --- | --- | --- | --- |
| **T1** | 模块划分 | **现在就抽 `jellyfish-tui`** | 沿 AGENTS.md「等真正开工再抽模块」 |
| **T2** | 渲染模型 | **声明式 Toolkit DSL**（`ToolkitApp` / `ToolkitRunner`） | 不用 `TuiRunner` 手写，不用立即模式 |
| **T3** | 线程模型 | **TUI 版 `ReActListener` + 有界增量缓冲 + 帧节流 + 投递回渲染线程** | 见 §3.1，本方案的核心 |
| **T4** | 视图语义 | **视图 = 会话投影，不持有第二份消息列表** | 见 §3.2；配一个「进行中回合暂存区」作为唯一例外 |
| **T5** | 键位（**第二轮已反转为「`Enter` 换行 / `Ctrl+S` 发送」**，见 §10.6） | `Enter` 换行 / `Ctrl+S` 发送 / `Ctrl+C` 退出 / **`Esc` 中断当前回合** | 见 §3.5 |
| **T6** | 范围切分 | **两步走**：T2.1 骨架（非流式）→ T2.2 流式与细节 | 见 §6 |
| **T7** | Markdown | **第二步做** | T2.1 纯文本；T2.2 评估 `tamboui-markdown`（需按 §1.1 的方法重新验 Java 8） |
| **T8.1** | 栏数 | **单栏** | 但代码写成 `DockElement` 且 `left` 位空着，加侧边栏不重构 |
| **T8.2** | 消息样式 | **角色前缀式**（`❯ 你` / `⏺ jellyfish`），内容左对齐 | 不用气泡（CJK 宽度对齐地狱）、不用 Panel 包裹（长会话噪音炸裂） |
| **T8.3** | 长会话性能 | ~~每帧全量重建 + `LazyElement`~~ → **冒烟否定；改：每帧把会话投影成单个 `RichText` + 自实现换行与切片**（§10.4 已裁决） | 每帧构建 N 个子元素在 ~120～180 个处布局断崖（>3000ms/帧） |
| **T8.4** | 输入框 | **多行 `TextAreaElement`** | 高度自适应**已实测成立**（5 行文本占 5 行，见 §1.4.B） |
| **T8.5** | 滚动跟随 | ~~`ScrollbarState.isAtEnd()`~~ → **智能跟随，判据改用我们自己的 `scrollOffset`**（§10.4 已裁决） | 框架的 `ScrollableElement` state 每帧归零 |
| **T8.6** | 焦点 | **不切换，焦点常驻输入框** | 消息区认 `PageUp`/`PageDown`/`End` 与**滚轮**（滚轮最初被放弃，T2.2 修复开启鼠标捕获，见 §10.6 / §11.9）；鼠标事件一律吞掉，正是为了保住这条 |
| **T8.7** | 中断视觉反馈 | 消息区补 `⎿ 已中断` + 状态栏短暂提示 + **输入框内容保留** | 用户可能只想改一下再发 |
| **T8.8** | 投影行数上限 | **只投影最近 N 条消息（默认 500 条）**，更早的以 `⎿ 更早的 N 条已折叠` 占位 | 每帧投影是 O(总行数)，必须有界（§10.4） |
| **T8.9** | 显示宽度 | **CJK 感知的宽度与换行自实现在 `jellyfish-tui`**，不进内核 | 它是纯渲染关注点（§10.4） |

---

## 1. 现状基线（实测）

### 1.1 TamboUI 在 JDK 1.8 的可用性 —— 已实测通过

> **结论：可用。** 四层证据，全部在 JDK `1.8.0_504`（Temurin）上实测。

**① 字节码层面：0.5.0-SNAPSHOT 是 multi-release JAR。**

| 模块 | 主干类 | `META-INF/versions/11/` |
| --- | --- | --- |
| `tamboui-core` | 200 个 → **major 52** | 1 个（`module-info.class` → 55） |
| `tamboui-tui` | 58 个 → **major 52** | 1 个 |
| `tamboui-widgets` | 159 个 → **major 52** | 1 个 |
| `tamboui-toolkit` | 95 个 → **major 52** | 1 个 |
| `tamboui-css` / `tamboui-annotations` / `tamboui-jline3-backend` | 全部 **major 52** | 1 个 |

manifest 带 `Multi-Release: true`。**major 55 只出现在 `META-INF/versions/11/module-info.class`**，JVM 8 会忽略该目录。

> **踩坑记录**：抽查第一个 class 时取到的正是那个 `module-info`，一度误判「只有 Java 11 字节码」。**核查这类库必须全量统计 class 版本分布，不能抽查单个文件。**

**② 编译层面**：独立探针工程设 `maven.compiler.source/target=1.8`，JDK 1.8 的 `javac` 编译**零错误**。

**③ 运行层面（真实 PTY，非管道）**：两条 API 都跑通，含备用屏进入/退出、渲染、按键、退出码。

```
TuiRunner 手写式：
  \e[?2027h \e[?1049h \e[?25l ... \e[1;1H\e[0mSMOKE-OK... \e[?1049l CLEAN-EXIT   → exit 0
Toolkit DSL：
  同上，含中文括号与 placeholder「说点什么…」正常渲染                        → Ctrl+C 干净退出
```

> **踩坑记录**：`pty.fork()` 后必须用 `ioctl(TIOCSWINSZ)` 显式设置窗口尺寸（如 80×24）。**PTY 尺寸为 0 时 TamboUI 一块像素都不画**，会误判成「库不工作」。

**④ JLine 3.25.1** 主干 468 个类为 major 52；21 个 major 65 的类全部位于 `org/jline/terminal/impl/ffm/**`（Java 21 FFM 终端后端），Java 8 下不会被加载——已由 ③ 的实跑证实。

**⑤ 官方口径**：TamboUI docs 的 Requirements 明确写 **"Java 8 or later (Java 17+ highly recommended)"**。

**⚠️ 连带发现（必须写进编码约定）**：TamboUI 官方文档示例大量使用 **Java 9+/17+ 语法**（`var`、`switch (event) -> case KeyEvent k when ...`）。**照抄示例必然编译失败**，必须改回匿名内部类 / 显式类型。这意味着社区示例基本不能直接复用，只能对着 javadoc（`javap`）写。

**⚠️ 依赖面**：`tamboui-core` 的 pom 把 `assertj-core` 声明为 `compile` 依赖，但带 `optional=true`，**不会传递**，shade 时无需特殊处理。

**⚠️ 体积**：`tamboui-{tui,toolkit,widgets,core,css,annotations,jline3-backend}` 合计约 1.1MB，JLine 3.25.1 约 1.4MB。进 fat jar 可接受。

### 1.2 现有外壳接缝（已就绪，本方案不改内核）

- **智能入口**：`AgentHarness.chat(sessionId, userInput, listener)` → `ReActTurn`（`await()` / `cancel()` / `isDone()`）。
- **流式回调**：`ReActListener` 的 7 个默认方法（`onText` / `onThinking` / `onToolCallStarted` / `onToolCallCompleted` / `onComplete` / `onCancelled` / `onError`），**全部回调在同一 `react` 线程**。
- **命令入口**：`CommandManager.isCommand(input)` / `execute(input, sessionId)` / `commands()` / `renderHelp()`；三态 `CommandResult.Kind`（`OK` / `ERROR` / `UNKNOWN`）。
- **会话入口**：`SessionManager.current()` / `require(id)` / `switchTo(id)` / `messagesOf(id)` / `todosOf(id)`；`Session` 的 `getMessages()` / `getAgentId()` / `getProvider()` / `getModel()` / `getPermissionMode()` / `getUsage()`。
- **生命周期**：`AgentHarness.bootstrap()` / `shutdown()`，`shutdown()` 四个收敛步骤**全部幂等**，`Launcher` 的「shutdown hook + finally」双保险可直接复用。
- **`Launcher.modeFor`** 已预留 `case TUI:` 分支，只需把构造参数从 `new TuiRunMode(console)` 换成完整接线。
- **`TuiRunMode` / `PlaceholderRunMode`** 已在位：`isImplemented()` 改 `true`、`run()` 换成真实现即可。

**✅ 关键确认：会话是按「轮」落库的，不是按 token。**

`ReActLooper.loop()` 中 `sessionManager.appendMessage` 只出现在三个点：

| 时机 | 落库内容 | 说明 |
| --- | --- | --- |
| 回合开始（`execute`） | `LlmMessage.user(userInput)` | 用户消息**立即**进 Session |
| **每轮模型响应聚合完成后** | `assistantMessage(response, toolCalls)` | **该轮的全部流式 token 到这一刻才落库** |
| 每个工具执行完 | `LlmMessage.tool(id, name, output)` | 工具结果**立即**进 Session |

**推论**：流式进行中，当前轮的增量文本**不在 `Session` 里**。因此「视图 = 会话投影」（T4）必须配一个**进行中回合的暂存区**，二者合并才是屏幕上的完整内容。这是 §3.2 的全部由来，也是本方案唯一一处「第二份数据」。

### 1.3 三个「看框架脸色」的未知项 —— ✅ 已冒烟，结论见下

`javap` 能看出 API 存在，但看不出运行时行为。三项已在 JDK 1.8 + 真实 PTY 上实测完毕，**结论与对方案的影响见 §1.4**。

| # | 未知项 | 结论 |
| --- | --- | --- |
| **A** | `Toolkit.lazy(Supplier)` 是否跳过不可见子元素 | ❌ **否定**，且暴露了更根本的瓶颈 |
| **B** | `TextAreaElement` 高度能否随内容自适应 | ✅ **成立** |
| **C** | 无事件时是否持续重绘 / `runLater` 语义 | ✅ 有结论（既是好消息也是约束） |

### 1.4 冒烟实测结果（T2.1 步骤 0）

#### A：`lazy()` 不跳过不可见子元素；真正的瓶颈是**布局容器的子元素个数**

| 实验 | 结果 |
| --- | --- |
| `scrollable().add(200 × lazy(...))`，2 秒 | `frames=1`，supplier 调用 **224 次 ≈ 全量 200 + 可见 24** → `lazy` 无任何跳过效果 |
| 纯构造 240 个子元素（不布局） | **0.209 ms/帧** |
| 纯构造 1000 个子元素（不布局） | **0.536 ms/帧** → **构造便宜且线性** |
| `column` 120 个子元素 | 38.8 ms/帧（正常，约 26fps 上限） |
| `column` 240 个子元素 | **> 3000 ms/帧** |
| `scrollable` 120 个子元素 | 38.8 ms/帧 |
| `scrollable` 180 个子元素 | **> 3000 ms/帧** |

> **瓶颈定位**：不是文本量，是**一次布局里的子元素个数**。约 **120～180 个子元素处出现断崖**（38ms → >3000ms，超线性爆炸）。因此「每帧把整份会话铺成 N 个子元素」**无论有没有 `lazy` 都不成立**。

#### A′（意外收获）：单个 `RichText` 承载整份会话完全可行

| 实验 | 结果 |
| --- | --- |
| `richText` 3000 行 × 每行 **4 个着色 Span** | 38.7 ms/帧，**构建仅 1.96 ms/帧** |
| `richText` 1000 行 × 4 Span | 38.7 ms/帧，构建 1.32 ms |
| `text()` 3000 行（无样式） | 38.8 ms/帧，构建 0.62 ms |

> **这才是正确的载体**：把整份会话投影成**一个** `RichText`，行内用 `Span` 承载角色前缀的着色——**恰好就是 T8.2 已裁决的「角色前缀 + 缩进」纯文本视觉语法**。

#### A″：`ScrollableElement` 不适合本场景

| 事实（`javap -c` + 实测） | 影响 |
| --- | --- |
| `ScrollbarState` 是 `ScrollableElement` 的**实例字段**，构造期新建 | 每帧 `new` 一个 scrollable ⇒ 滚动位置每帧归零 |
| `ContainerElement.add()` 只是 `List.add`，**无按 `id` 替换** | 复用实例逐帧 `add` ⇒ 子元素无限增长 ⇒ 几十秒内撞上 §A 的断崖 |
| 实测自建 `ScrollbarState` 传入时 `contentLength=1` | 内容高度不由外部提供 |

> 结论：**「每帧内容都变 + 滚动位置要保留」这个组合，框架的滚动容器做不到**，必须自己实现。

#### B：`TextAreaElement` 高度自适应 ✅

`dock().center(text("FILLER")).bottom(textArea(state).wrapWord())`，state 含 5 行文本 → 实测渲染在**第 20～24 行，恰好 5 行**。**T8.4 的多行自适应成立**。

#### C：重绘语义

| 实验 | 结果 |
| --- | --- |
| 内容恒定不变，3 秒 | `frames=76`（约 **26 fps 持续重绘**） |
| `runLater(Runnable)` 从后台线程提交 | 执行线程 = **`main`**（即渲染线程） |
| `runOnRenderThread(Runnable)` 从后台线程提交 | 执行线程 = **`main`** |
| `render()` 抛异常 | ToolkitApp 渲染**红色 ERROR 面板**，不静默、不崩进程 |

> **双面影响**：① 好消息——流式期间不需要自己造心跳，引擎本来就在重绘；② 约束——**每帧都会调用 `render()`**，所以「每帧的投影成本」是真实成本，必须按帧预算（~38ms）来设计，不能假设「没变化就不调用我」。

#### 冒烟过程中踩到的三个坑（写进编码约定）

1. **`Toolkit.scrollable(Element...)` 这个构造形式根本不渲染内容**——实测只画出滚动条，24 行内容一个字符都不出。必须用 `scrollable().add(...)`。
2. **`Span.styled(text, null)` 会 NPE**（`Span.computeHashCode` 对 null `Style` 解引用）。无样式用 `Span.raw(...)`，要样式用 `Style.EMPTY.xxx()`。
3. **PTY 探针必须 `ioctl(TIOCSWINSZ)` 设窗口尺寸**，否则尺寸为 0，TamboUI 什么都不画（已在 §1.1 记录）。

---

## 2. 目标态

### 2.1 模块与依赖边（本轮新增）

```mermaid
flowchart LR
    CLI["jellyfish-cli<br>main / Launcher / RunMode<br>【改】modeFor(TUI) 完整接线"]
    MODE["TuiRunMode<br>【改】薄接线，真实现"]
    TUI["jellyfish-tui<br>【新】TamboUI 界面 + TUI 版监听器"]
    HARNESS["AgentHarness<br>（core，唯一智能入口）"]
    CMD["CommandManager<br>（infra，命令域）"]
    SESS["SessionManager<br>（infra，会话域）"]
    TB["TamboUI toolit/tui/widgets<br>+ JLine 3.25.1"]

    CLI --> MODE
    MODE ==>|"chat / await / cancel"| HARNESS
    MODE -->|"装配"| TUI
    TUI ==>|"chat / await / cancel"| HARNESS
    TUI ==>|"isCommand / execute"| CMD
    TUI ==>|"current / messagesOf"| SESS
    TUI --> TB

    classDef cli fill:#E9F7EF,stroke:#2E8B57,color:#123
    classDef tui fill:#FDF2E3,stroke:#C77B00,color:#123
    class CLI,MODE cli
    class TUI tui
```

**TUI 依旧只有「外壳 → 内核」的调用，没有反向边**：不注册扩展点、不发事件、不碰注册表。

**为什么 `RunMode` 实现留在 `jellyfish-cli` 而不是 `jellyfish-tui`**：`RunMode` / `StartupOptions` / `ExitCodes` 都定义在 `jellyfish-cli`。若把 `TuiRunMode` 放进 `jellyfish-tui`，后者就要依赖前者，而前者又必须依赖后者来用界面——**直接构成循环依赖**。因此 `TuiRunMode` 保持薄接线（与 `CliRunMode` 对称），重活全在 `jellyfish-tui`。这也正是 `cli方案.md` §0.4 里「`Launcher` 与参数解析零改动」得以成立的原因。

### 2.2 包结构与文件清单

```
jellyfish-tui/pom.xml                                    # 【新】模块 POM（已建）
jellyfish-tui/src/main/java/zcd/jellyfish/tui/
├── TuiApp.java                     # 【新】唯一入口：建 ToolkitRunner、装配视图与监听器、跑事件循环、返回退出码
├── ChatShell.java                  # 【新】版式（DockElement 三段式）：投影 → 切片 → 单 RichText
├── ChatState.java                  # 【新】视图状态：scrollOffset / followTail / 脏标记（只在渲染线程读写）
├── InflightTurn.java               # 【新】进行中回合的暂存区（有界），T4 的唯一例外
├── TranscriptProjector.java        # 【新】纯函数：会话消息 + 暂存区 + 宽度 → List<VisualLine>
├── StatusBarView.java              # 【新】状态栏：agent · provider/model · 权限模式 · token 用量
├── ChatInputView.java              # 【新】多行输入框 + 键位覆写（Enter 换行 / Ctrl+S 发送 / Esc 中断）
├── ShellCommand.java               # 【新】/exit 截胡：外壳与用户之间的约定，不进命令注册表
├── CommandCompletion.java          # 【新】命令补全状态：激活判定 / 前缀过滤 / 选中 / 接受 / 收起（纯逻辑）
├── CommandCompletionView.java      # 【新】补全面板渲染：候选 → List<VisualLine>（纯函数）
├── CommandChoicePicker.java        # 【新】二级选择页状态：命令候选 / 选中 / 上下移动 / 确认 / 收起（纯逻辑）
├── CommandChoicePickerView.java    # 【新】二级选择页渲染：候选 → List<VisualLine>（纯函数）
├── Overlay.java                    # 【新】输入框上方浮层面板：标题 + 视觉行（补全面板与选择页共用）
├── TuiReActListener.java           # 【新】ReActListener 的 TUI 实现（react 线程 → InflightTurn）
└── text/                           # 【新】文本布局原语（与 TamboUI 解耦，便于单测）
    ├── DisplayWidth.java           # CJK 感知的显示宽度（T8.9）
    ├── StyledSegment.java          # 一段带样式的文本
    ├── VisualLine.java             # 一个视觉行 = 若干 StyledSegment
    └── LineWrapper.java            # 前缀 + 换行：把一条逻辑行展开为若干视觉行

jellyfish-tui/src/test/java/zcd/jellyfish/tui/
├── ChatStateTest.java              # 【新】
├── InflightTurnTest.java           # 【新】
├── CommandCompletionTest.java      # 【新】
├── CommandCompletionViewTest.java  # 【新】
├── TranscriptProjectorTest.java    # 【新】
├── TuiReActListenerTest.java       # 【新】
└── text/DisplayWidthTest.java、text/LineWrapperTest.java   # 【新】

jellyfish-cli/src/main/java/zcd/jellyfish/cli/
├── mode/TuiRunMode.java            # 【改】占位 → 真实现（薄接线）
└── mode/PlaceholderRunMode.java    # 【改】仅剩 ServerRunMode 使用，类注释同步
jellyfish-cli/src/main/resources/
├── log4j2-tui.xml                  # 【新】TUI 模式专用：root → 文件，绝不碰 stderr
└── log4j2.xml                      # 【不改】

jellyfish-cli/pom.xml
└── <dependencies>                  # 【改】新增 zcd:jellyfish-tui

pom.xml（parent）
├── <modules>                       # 【改】新增 jellyfish-tui
└── dependencyManagement            # 【不改】tamboui-bom 已在位
```

**`jellyfish-cli` 侧改动的收敛**：只有 `Launcher.modeFor` 的 TUI 分支（把 `new TuiRunMode(console)` 换成完整接线）+ `TuiRunMode` 重写 + 一处日志配置切换。`StartupOptions` / `StartupOptionsParser` / `SessionBootstrap` / `ExitCodes` / 其余八个 Dagger `Module` **一行不动**。

**为什么 `jellyfish-tui` 不引入 Dagger**：`TuiApp` 的构造需要运行期参数（`StartupOptions`、`AgentHarness`、`CommandManager`、`SessionManager`），不是纯装配。DI 是 `jellyfish-cli` 这层 composition root 的职责，`jellyfish-tui` 只暴露普通构造器，保持可单测。

### 2.3 版式与视觉语法

```
╭─ jellyfish · 默认会话 ─────────────────────────────╮   ← Dock.top   （亮起时显示会话标题）
│  ❯ 帮我把 README 里的 TODO 清掉                    │
│                                                    │
│  ⏺ jellyfish                                       │
│    我先看一下 README。                              │
│      ⎿ read_file · 1.2k · ✓                        │
│      ⎿ edit_file · ✓                               │
│    已清掉 3 个 TODO。                               │
│                                                    │
│  ⏺ jellyfish                                       │
│    稍等，我再看一眼…                                 │   ← 进行中回合的暂存区渲染在这里
│                                                    │
│  ❯ /help                                           │   ← 命令回显（与用户消息同形）
│    ⎿ 可用命令（10 条）：                             │   ← 命令输出：块首行带前缀
│        /agent [agentId]  查看或切换 agent            │   ← 续行 6 列悬挂缩进 + 原始缩进
│    ! 未知命令：/foo（输入 /help 查看可用命令）        │   ← 警告态（不是失败，但要看得见）
│    ✗ 模型不存在：foo/bar                            │   ← 错误态
╰────────────────────────────────────────────────────╯   ← Dock.center（scrollable，fill）
╭─ 输入 ─────────────────────────────────────────────╮
│ 把 server 那段也一起处理␊                          │   ← 多行 TextArea（1～6 行）
│ 顺便跑一下测试                                      │
╰────────────────────────────────────────────────────╯
 main · claude-sonnet-4.5 · normal · 12.4k/200k      ← Dock.bottom（状态栏，1 行）
 └agent  └provider/model   └权限模式  └token 用量
```

**视觉语法只有两条规则**（这是 T8.2 选角色前缀式的核心理由——**一套规则表达全部层级**）：

| 层级 | 前缀 | 缩进 | 样式 |
| --- | --- | --- | --- |
| 用户消息 | `❯ ` | 1 级 | 默认色 |
| 助手消息 | `⏺ jellyfish` | 1 级 | 默认色 |
| 助手正文 | — | 2 级 | 默认色 |
| 工具轨迹 | `⎿ ` | 3 级 | dim |
| 思考过程（T2.2 折叠） | `␥ ` | 3 级 | dim + italic |
| 中断/错误 | `⎿ 已中断` | 3 级 | 黄 / 红 |
| 命令回显 | `❯ ` | 1 级 | 默认色（与用户消息同形：命令就是用户敲的） |
| 命令输出 | `⎿ ` / `! ` / `✗ ` | 2 级（6 列，**仅块首行**） | 默认色 / 黄 / 红 |

**外壳提示为什么不复用工具轨迹的样子**（T2.2 修复）：两者都叫「结果」，但性质相反——工具轨迹是模型做的事（3 级、dim、不希望用户细读），命令输出是用户主动要的（2 级、正文色、应当好读）。因此提示自成一个块：命令原文回显成一行 `❯ /help`，输出块首行带 `⎿ `/`! `/`✗ `，续行用 6 列**悬挂缩进**而不是逐行重复前缀（逐行一个箭头会把列表变成一摧箭头，还会持续吃掉左边界）。

**不用气泡的两个具体理由**（不是口味问题）：
1. **CJK 宽度对齐**——中文占 2 列，气泡右对齐要按**显示宽度**而非 `String.length()` 计算；还要处理 `east-asian-width` 与 emoji。角色前缀式完全绕开这个地狱。
2. **样式复用**——工具轨迹、思考块、错误行都要「缩进 + 前缀」；气泡式会逼出三套互不相干的组件。

**分区实现**：`DockElement` 三段式，`center` = 消息区（`ScrollableElement`，`fill()`）、`bottom` = 输入框 + 状态栏（`length(...)`）、`top` 留给未来的标签栏。**`left` 位刻意空着**——第二步加会话侧边栏就是加一行 `.left(sessionList, percent(25))`，不重构（T8.1）。

---

## 3. 设计细节

### 3.1 线程契约（本方案的核心）

**进程里同时存在三条执行线**：

| 线程 | 谁在里面跑 | 可以做什么 | **绝对不可以做什么** |
| --- | --- | --- | --- |
| **渲染线程**（TamboUI 事件循环） | `ToolkitRunner.run(...)` 阻塞于此；`render()` 与按键回调都在这 | 读写 `ChatState`、构建 `Element` 树、调 `SessionManager`/`CommandManager`（命令是**同步**的） | 调 `await()` 阻塞（会冻住界面） |
| **`react` 池线程**（core） | `ReActLooper` 循环；`ReActListener` 7 个回调都在这 | **只往 `InflightTurn` 追加原始增量**、置脏标记 | **绝不碰 `Element` 树、绝不调 TamboUI API** |
| **`llm-stream` 池线程**（infra） | SSE 读流 | —（对 TUI 不可见） | — |

**契约一句话**：**所有界面状态变更都发生在渲染线程；`react` 线程只做「往线程安全的暂存区追加字节 + 置一个 volatile 脏标记」。**

这条契约的好处是它**不需要**依赖「`runLater` 到底在哪个线程执行」这种我没验证过的语义（§1.3 未知项 C）：即使 `runLater` 的行为与预期不符，最坏情况也只是刷新延迟，而不是数据竞争或界面崩坏。

**数据交换的唯一载体**：`InflightTurn`（§3.2），内部 `StringBuilder` 只由 `react` 线程写、渲染线程读，**读写边界由 volatile 脏标记隔开**：

```
react 线程                                    渲染线程（render()）
  onText(delta)                                 if (!dirty) return 上一帧的元素树
    ├─ inflight.append(round, delta)            drain = inflight.drainIfDirty()
    ├─ dirty = true            ──────────▶      chatState.apply(drain)
    └─ 不请求重绘                                构建 Element 树
```

**为什么不让 `react` 线程直接请求重绘**：`Esc` 中断后必须**立刻**停笔（否则用户按了没反应，会以为卡死）；若存在「已排队但未执行的重绘」，中断后的残余帧可能又画回来。因此中断路径**由渲染线程自己判断**（读 `ReActTurn.isDone()` / `currentTurn` 的取消位），不依赖 `react` 线程的投递。

### 3.2 视图投影与暂存区（T4 的唯一例外）

**投影公式**：

```
屏幕上的消息区 = Session.getMessages()   ⊕   InflightTurn（当前回合的增量）
                 └─ 权威、持久、按轮落库     └─ 易失、仅存活于回合进行中
```

**`InflightTurn` 的职责与边界**：

| 项 | 约定 |
| --- | --- |
| 内容 | 当前回合**尚未落库**的助手增量文本 + 该回合已发生的工具轨迹（进行中/已完成） |
| 上限 | **有界**：单回合文本上限（如 256KB）+ 轨迹条数上限。超限后丢弃最旧增量并置「已截断」标记，**绝不无界增长**（对齐 AGENTS.md 对事件通道「有界队列」的一贯要求） |
| **清空时机** | 收到 `onToolCallStarted`（说明该轮已落库、即将进工具）、`onComplete`、`onCancelled`、`onError` 时清空 |
| 清空为什么必须晚于落库 | `ReActLooper` 先 `appendMessage(assistant)` 再 `onComplete`，顺序天然安全。若清空早于落库，屏幕上会出现**正文闪一下就不见** |
| 线程安全 | `append` 只由 `react` 线程；`drain` 只由渲染线程；靠 volatile 脏标记 + 一次性 swap 交接 |

**为什么这不算违反 T4**：T4 禁的是「视图自己维护一份**会话消息列表**」——那份数据的权威在 `SessionManager`，视图复制它会带来缓存失效类 bug。而 `InflightTurn` 装的是**还没成为会话消息的东西**，它在 `Session` 里**不存在**，没有第二权威可言。回合终结即清空，生命周期与 `ReActTurn` 严格对齐。

**中间轮次文本的处理 —— TUI 比 CLI 简单**：CLI 的 `CliReActListener` 要把「工具调用前的模型文本」当轨迹转写到 stderr，是因为 stdout 必须严格等于最终回答。**TUI 没有这个约束**：那条 assistant 消息确实已落进 `Session`（`appendMessage(assistantMessage(response, toolCalls))`），它会作为历史正常显示。因此 `TuiReActListener` **不需要** CLI 那套 `flushTrace()` 逻辑——这是两套监听器**唯一不能复用的地方**，也是它们必须分开的原因。

### 3.3 `TuiReActListener`

与 `CliReActListener` 同构（都实现 `ReActListener`），但落点完全不同：

| 回调 | CLI（`CliReActListener`） | TUI（`TuiReActListener`） |
| --- | --- | --- |
| `onText` | 攒进 `StringBuilder`，收敛时写 stdout | `inflight.appendText(round, delta)` + 置脏 |
| `onThinking` | 可选写 stderr（`· ` 前缀） | `inflight.appendThinking(delta)`（T2.1 并入正文，T2.2 折叠） |
| `onToolCallStarted` | 转写轨迹到 stderr + **清空缓冲** | `inflight.beginTool(name)` + **清空文本缓冲** |
| `onToolCallCompleted` | stderr 报长度 | `inflight.endTool(id, success)` |
| `onComplete` | 写 stdout 回答 + 截断警告 | `inflight.finish(TRUNCATED?)` + 置脏 |
| `onCancelled` | 转写残片 + stderr | `inflight.finish(CANCELLED)` + 置脏 |
| `onError` | stderr | `inflight.finish(ERROR, message)` + 置脏 |
| `isFailed()` | **有**（供调用点避免重复打印） | **不需要**——TUI 里错误进消息区，调用点无需再打一遍 |

**`round` 从哪来**：`ReActListener` 的回调**不带轮次号**，`InflightTurn` 自己按「`onToolCallStarted` 出现」计数。这够用，因为清空时机也锚在同一批回调上。**不为了拿轮次号去改 `jellyfish-core`**——那是无谓的内核改动。

### 3.4 帧节流（T2.2）

流式 token 可达每秒几十次；若每个 token 都触发重绘，CPU 会被打满。

- **T2.1（非流式）不需要节流**：回合走 `await()`，一次原子追加，一次重绘。
- **T2.2（流式）方案**：`react` 线程只置脏标记、不请求重绘；渲染线程在每帧开头读脏标记，**有脏才应用增量并构建元素树**，无脏直接复用上一帧。
- **保底刷新**：若 §1.3 未知项 C 的结论是「无事件时不重绘」，则用 `ToolkitRunner.scheduleRepeating(..., 50ms)` 作为保底心跳（20fps 上限，对人眼足够，对 CPU 温和）。
- **不做 token 级去抖**（debounce）：去抖会引入「说完最后一个字还等 200ms」的迟滞感。用**帧率上限**而不是延迟聚合，语义更干净。

### 3.5 键位与焦点（T5 + T8.6）

| 按键 | 行为 | 归属 |
| --- | --- | --- |
| `Enter` | 补全面板可见时**接受选中项**，否则**换行**（`\r` 与 `\n` 都是） | 补全面板 / 输入框 |
| `Ctrl+S` | **发送** | 外壳 |
| `Esc` | **中断当前回合**（`ReActTurn.cancel()`）；无进行中回合时无副作用 | 外壳 |
| `Ctrl+C` | **退出** | 外壳 |
| `PageUp` / `PageDown` | 消息区翻页 | 消息区（输入框不消费） |
| 滚轮上/下 | 消息区滚动 3 行 | 消息区（全局处理器，见 §11.9） |
| `End` | 跳到底部并恢复跟随 | 消息区 |
| `↑` / `↓` | 浮层面板（补全 / 二级选择页）可见时移动选中项，否则输入框内移动光标 | 浮层面板 / 输入框 |
| 其余 | 全部进输入框 | 输入框 |

**焦点常驻输入框（T8.6）**：聊天场景 95% 的时间在打字，为次要操作引入模式切换不划算。消息区只认上表里那几个滚动键（`End` 归消息区，代价是编辑器的「光标到行尾」不可用）。**鼠标事件（含点击）一律不改变焦点**：捕获开启后非滚轮事件会被外壳吞掉，理由见 §11.9。

**为什么是反转键位（实测推翻原方案）**：原方案是「`Enter` 发送 + 修饰键换行」，冒烟证明它**在全部终端上都不可实现**——框架的键盘解码器不解析任何修饰键编码，`Shift+Enter`（`ESC \r`）、CSI-u（`ESC [13;2u`）、CSI-27（`ESC [27;2;13~`）三种编码一律落成 `UNKNOWN`，`hasShift()`/`hasAlt()` **永远是 false**；而裸 `\r` 与 `\n` 都解码成同一种不带修饰符的 `ENTER`。既然「哪个 `Enter`」无从区分，就只能让**别的键**承担发送。`Ctrl+S` 被选中是因为：它解码成 `CHAR` + `ctrl` + 码点 `115`（与 `Ctrl+C` 同族、可稳定识别），且实测框架进 raw 模式时已关掉 `IXON`，**不会**被终端的 XOFF 流控吞掉。同理，`Ctrl+J` 不可用——它就是 `\n`，与裸回车无法区分。

**滚轮：最初放弃，第二轮修复（见 §11.9）**。滚轮事件属于鼠标捕获（`mouseCapture`），当初的权衡是「开启后终端选择被应用截走，复制须按住修饰键」，判定这个代价换不来滚轮。实测发现关掉捕获时滚轮**根本到不了应用**（不少终端会把备用屏下的滚轮翻译成 `↑`/`↓`，而这两个键归补全导航），于是改为**默认开捕获 + `-Djellyfish.tui.mouseCapture=false` 逃生门**：滚轮由 `MouseScrollMapper` 认领，其余鼠标事件吞掉以保住焦点。

**⚠️ 原记的实现风险已被实测结论取代**：`TextAreaElement` 确实会吃掉 `Enter`，且**父级装饰器无法抢先**（按键是冒泡的，父级只能收到没人要的键）。因此最终没有在路由层截胡，而是**自己实现输入元素**：直接渲染 `TextArea` 部件、自己决定键位归属。详见 §11.2 D1。

**中断的视觉反馈（T8.7）**：消息区补一行 `⎿ 已中断`；状态栏右侧短暂显示「已中断」；**输入框内容保留**（用户可能只是想改一下再发）。

**中断为什么不沿用 CLI 的「Ctrl+C 整体退出」**：CLI 单次执行没有「当前回合」可言，进程退出即可。TUI 有长驻界面，用户按 `Esc` 的意图是「别再说了」而不是「退出程序」，因此中断必须是**回合级**而非进程级。

### 3.5.1 命令补全（输入「/」弹出可用命令）

**触发条件刻意很窄**：输入只有一行、以 `/` 开头、且 `/` 之后到行尾**没有空白**。也就是说「正在打命令名」才弹，一旦敲空格（开始给参数）就收起。理由有两条：参数字典命令域没有，假装能补只会给出错误候选；`/mode` 这类命令的参数是自由文本，敲空格往往就是不想看列表了。

**面板形态**：非浮层，而是插在输入框与消息区之间的一个圆角面板（标题「命令」），高度 = 可见行数 + 边框；`ChatShell.messageAreaRows(...)` 把它的高度算进高度账本，消息区相应变矮。之所以不做浮层覆盖：`DockElement` 的 `bottom` 天然就是「底部占多少行」，多一路叠加只会多一处与布局断点（§10.4）打交道的代码。

**候选来源**：`CommandManager.commands()` **每帧现算**（无缓存，插件热部署后立刻可见），按**命令名或别名**做前缀匹配（大小写不敏感），命令名升序。最多显示 8 行，选中项尽量居中；宽度按 `DisplayWidth` 截断，因此中文名/说明不会错位。清单尾部额外的 `/exit` 由外壳自己追加（它是外壳自有命令，不进内核注册表，见 `ShellCommand`）。

**键位归属**：`↑`/`↓` 移动选中，`Enter` 确认：该命令有可选值时**直接打开二级选择页**（走只读候选查询，不执行命令，因此不会误触 `/new` 这类副作用），没有可选值时回填命令名（不直接执行）；面板没弹时这两个键一律不归外壳管，`↑`/`↓` 落回输入框做光标移动、`Enter` 落回输入框换行。`Tab` 已取消、不再参与外壳键位。
**为什么 `Enter` 可以既是换行又是选中**：选中只在补全面板可见时发生，而面板只会在「正在打命令名」时出现；其余全部时间它都被原样放行给输入框。因此不需要第二个 `Enter`，也不与 T5 的反转键位（§3.5）冲突——反转键位否定的是「用修饰键区分换行与发送」，不是「`Enter` 不能有第二种含义」。

**`Esc` 的双重语义**：面板可见时先收起面板（并记住当前命令词），同时照旧调用 `ReActTurn.cancel()`（无回合时是空操作）。收起后只要命令词不变就不重开——否则下一帧由输入文本重算时又会弹回来，看起来像按键失灵。

**`accept` 的替换语义**：用规范命令名回填（用别名匹配到也回填命令名）；命令带用法片段时补一个空格，方便直接接着打参数，不带用法则只留命令名，避免发送时多一个尾随空格。

### 3.5.2 二级选择页（命令要求挑一个取值）

`/agent`、`/model`、`/mode`、`/resume` 这类命令不带参数时需要用户从若干取值里挑一个，而“有哪些取值”只有命令自己知道。因此命令把结构化候选（`CommandChoice`）交给外壳渲染二级选择页，而不是让外壳去猜属于命令域的候选集。触发时机是**选中命令**：补全面板里按 `Enter` 选中时先做一次只读候选查询，有候选就弹页（不执行命令）。

**候选来源**：两条路径共用同一批构造函数——命令执行（无参）时用 `CommandResult.choices(...)` 带回候选（直接敲 `/agent` + 发送也弹选择页）；外壳“选中命令”时则走只读查询 `CommandManager.options(name, sessionId)`（扩展点 `CommandOptionRequest` → `CommandOptions`），**不执行命令**；没有候选的命令返回空列表。不认识候选的外壳（CLI）忽略它、照旧打印 `getOutput()`，所以这是向后兼容的增量能力。插件命令注册候选处理器即可获得同样的选择页，外壳不掺业务知识。

**面板形态**：与补全面板共用输入框上方的同一个位置（`Overlay` 抽象：标题 + 行），高度同样进 `messageAreaRows` 账本；标题就是触发它的命令原文（如 ` /agent `）。两者状态互斥：选择页优先于补全面板。

**键位归属**：`↑`/`↓` 移动选中，`Enter` 确认（拼出 `命令原文 + 空格 + 取值` 并执行），`Esc` 取消，`PageUp`/`PageDown`/`End` 与 `Ctrl+C` 仍归外壳。选择页是**模态**的：其余按键一律吞掉，页面保持到 `Esc` 为止，输入框不会在页面背后被改动。

**会话切换的连带效果**：`/resume` 确认后选中的会话立即生效；外壳不缓存 `sessionId`，下一帧渲染自然读到新会话（§3.4）。

### 3.6 滚动跟随（T8.5 智能跟随）

状态：`ChatState.followTail`（默认 `true`），**只在渲染线程读写**。

```
每帧末（元素树已构建、滚动区已渲染）：
  followTail = scrollbarState.isAtEnd()      ← 用户滚上去了就自动为 false

新内容到达（Session 消息数变化，或暂存区从空变为非空）：
  if (followTail)  scrollbarState.last()     ← 跟随底部
  else             显示底部提示条，不滚动
```

**提示条文案分两态**：

| 场景 | 文案 |
| --- | --- |
| T2.1（原子追加） | `↓ N 条新消息`（N = 追加后条数 − 追加前条数） |
| T2.2（流式生成中） | `↓ 正在生成…`（token 没有「条数」概念，报条数会变成一直跳动的 `↓ 1`） |

**为什么用 `isAtEnd()` 每帧回读而不是维护一个「用户意图」标志**：立即模式下没有可靠的方式区分「这次滚动是用户操作还是程序追加」。回读 `isAtEnd()` 把判断锚在**可观测的事实**（是否在底部）上，而不是**推断的意图**上，天然免疫误判。

### 3.7 日志隔离（**必须做，否则界面必坏**）

TUI 独占备用屏，**任何写 stderr 的日志都会直接糊在画面上**。当前 `log4j2.xml` 的 root appender 正是 `Console target=SYSTEM_ERR`（WARN 级）。

**方案**：新增 `jellyfish-cli/src/main/resources/log4j2-tui.xml`，root appender 换成**文件**：

```xml
<Configuration>
    <Properties>
        <Property name="logLevel">${sys:jellyfish.log.level:-WARN}</Property>
        <Property name="logFile">${sys:jellyfish.log.file:-jellyfish-tui.log}</Property>
    </Properties>
    <Appenders>
        <File name="TuiFile" fileName="${logFile}" append="true">
            <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger{36} - %msg%n"/>
        </File>
    </Appenders>
    <Loggers><Root level="${logLevel}"><AppenderRef ref="TuiFile"/></Root></Loggers>
</Configuration>
```

**切换时机**：`JellyfishApplication.run` 里，**参数解析之后、`DaggerJellyfishComponent.create()` 之前**设置系统属性 `log4j.configurationFile=log4j2-tui.xml`。

- `JellyfishApplication` 刻意**没有静态 `Logger` 字段**（见其类注释），因此「类加载不提前碰日志框架」这条既有约束天然成立，切换一定生效。
- 复用既有的「参数扫两遍」模式（`applyVerbosity`）：新增一个只看模式旗标的早扫描，与现有写法同构。
- **保留 `ConsoleIO`**：`Launcher` 在 TUI 进入备用屏**之前**还要报启动期错误（`bootstrap` 失败、参数不可满足），那一刻 stderr 是干净的。

### 3.8 打包（shade）

`jellyfish-cli/pom.xml` 的 shade 配置需要覆盖新增的依赖，**三处必须确认**：

| 项 | 说明 |
| --- | --- |
| `ServicesResourceTransformer` | **已在位**。JLine 的 provider 注册是 `META-INF/services/org/jline/terminal/provider/{jni,exec,ffm,jna,jansi}`（**服务文件名本身是个路径**，不是接口名），同名合并、异名直接复制，该 transformer 能正确处理 |
| JLine 原生库 | `org/jline/nativ/**`（含 `Mac/`、`Linux/`、`Windows/`、`FreeBSD/` 的 `.so`/`.dylib`/`.dll` 与 `jlinenative.properties`）。shade 默认保留资源，但**现有 filter 排除了 `META-INF/*.SF|DSA|RSA`，不要误伤 `org/jline/nativ/**`** |
| `META-INF/versions/**` | 我们**依赖 `Multi-Release: true`**（§1.1）。shade 是否保留该目录需实测；**丢掉也不影响 Java 8 运行**（只是少了 `module-info`），但要记录结论 |

**验证方式（T2.1 必做）**：`mvn package` 后，用**产出 fat jar** 在真实 PTY 里跑一遍 `-tui`。**不能只验 `mvn exec:java`**——那用的是 `target/classes` 与本地仓库 jar，绕开了 shade，原生库与 services 的问题一个都暴露不出来。

### 3.9 明确不做的事（本轮）

| 不做 | 理由 |
| --- | --- |
| 会话侧边栏、待办面板 | T8.1 单栏；`/session`、`/todo` 的文本输出作为系统消息显示在消息区 |
| 气泡样式 | T8.2 角色前缀式 |
| 渲染缓存 | T8.3 每帧全量重建 + `LazyElement`；引入缓存即违反 T4 |
| Markdown 渲染 | T7 第二步；且 `tamboui-markdown` 本地无缓存，需按 §1.1 的方法重新验 Java 8 |
| `-tui` 的 stdout 契约 | 备用屏把 stdout 占满逃逸序列（§1.1 实测），**TUI 模式 stdout 无任何契约**；退出后**不回显**会话内容 |
| 鼠标选中/复制、主题配置、`tui.json` 配置段 | 本轮无需求；不预设配置面 |
| 改 `jellyfish-core` 给 `ReActListener` 加轮次号 | 见 §3.3；`InflightTurn` 自己计数即可 |
| 权限 ASK 的人工审批弹窗 | 属 permission 模块的独立议题；TUI 的 `DialogElement` 能力已确认可用，届时再接 |
| TUI 模式下 `--show-thinking` | T2.1 思考并入正文；T2.2 折叠后再定义开关语义 |

---

## 4. `AGENTS.md` / `README.md` 同步清单

### 4.1 `AGENTS.md`

| 位置 | 改动 |
| --- | --- |
| 代码结构 → 模块表 | 新增 `jellyfish-tui` 行（坐标 `zcd:jellyfish-tui`，职责「TUI 外壳：TamboUI 界面、TUI 版 `ReActListener`、线程契约」，依赖 `jellyfish-api`/`jellyfish-infra`/`jellyfish-core`） |
| 代码结构 → 依赖方向图 | 新增 `TUI --> CORE/INFRA/API`，`CLI --> TUI` |
| 代码结构 → 目录树 | 新增 `jellyfish-tui/src/main/java/zcd/jellyfish/tui/` 子树 |
| 整体架构图 → 外壳入口 | `jellyfish-cli` 分支补 `-tui 已落地（交互式）` |
| 架构要点 → 三种启动模式 | `-tui` 从「待落地」改为「已落地」，补一句「界面层在 `jellyfish-tui`，`TuiRunMode` 只做薄接线」 |
| 架构要点 | 新增一条「TUI 的视图 = 会话投影 + 进行中回合暂存区」 |
| **`cli` 模块表描述** | 顺带修正：`mode/` 里 `-tui` 不再是占位 |

### 4.2 `README.md`

`-tui` 从占位改为可用，补运行示例与键位表。

### 4.3 其他文档

- `cli方案.md` §0.2 的「本轮不动」「开工前必须先冒烟」两段：标注**冒烟已完成，结论见 `tui方案.md` §1.1**（**只加交叉引用，不改原文口径**）。
- `cli方案.md` §0.4 的「开工 TUI 时抽 `jellyfish-tui`」：标记**已执行**。

---

## 5. 测试计划

**原则**：TamboUI 是终端框架，**它的渲染结果不适合做单测**（断言逃逸序列既脆又无意义）。因此：

> **把可测的逻辑从渲染里挤出来**——`InflightTurn`（纯数据 + 有界策略）、`ChatState`（投影与滚动决策）、`TuiReActListener`（回调 → 暂存区）**全部与 TamboUI 无耦合**，可纯单测。真正的「画出来对不对」交给 PTY 端到端冒烟（§7.2）。

| 测试类 | 覆盖点 |
| --- | --- |
| `InflightTurnTest` | 追加文本/思考/工具轨迹；**有界截断**（超限丢最旧 + 置截断标记）；清空时机的四种触发；`drain` 返回后内部为空 |
| `ChatStateTest` | 投影 = Session 消息 + 暂存区；暂存区清空后不重复显示；`followTail` 在 `isAtEnd()` 为真/假下的滚动决策；「N 条新消息」计数 |
| `TuiReActListenerTest` | 7 个回调各自的落点；`onToolCallStarted` **只**清文本不清工具轨迹；`onError` 后暂存区带错误态 |
| `TuiReActListenerTest`（并发） | 多线程 `append` + 单线程 `drain` 不丢增量、不抛 `ConcurrentModificationException` |
| `TranscriptViewTest` | 层级前缀与缩进映射正确（纯函数化的那部分） |
| `CommandCompletionTest` | 激活边界（只有 `/` / 已敲空格 / 多行 / 非 `/` 开头）；命令名与**别名**前缀匹配、大小写不敏感；无匹配仍激活；换词时选中复位；上下移动循环；`accept` 回填规范名与用法后补空格；`Esc` 收起后不重开、换词后允许重开 |
| `CommandChoicePickerTest` | 打开条件（空候选不弹）；默认选中「当前取值」；上下移动首尾循环；关闭后状态清理 |
| `CommandChoicePickerViewTest` | 行数 = 可见候选 + 1 行提示；选中行箭头与整行反白；当前取值标记；行宽不超可用列数；可见行数上限与窗口跟随 |
| `CommandCompletionViewTest` | 未激活返回空；无候选占位行；选中行标记与反白；最多 8 行；选中越出窗口时窗口跟随；**每行宽度不超可用列数**；`truncate` 按 CJK 显示宽度截断 |
| `TuiRunModeTest` | `isImplemented()` 为 `true`；`/exit` 被外壳截胡**而不**进 `CommandManager`；退出码映射 |

**不测**：`TuiApp` / `ChatShell` 的元素树形状（`javap` 不能断言，且改版式就碎）、TamboUI 内部行为。

**测试基建**：`jellyfish-tui/pom.xml` 需要 JUnit5 + Mockito（版本由父 POM BOM 管），与其余模块一致。

---

## 6. 实施阶段（每阶段结束都可编译、可测试）

### T2.1 骨架（非流式）

| 步骤 | 内容 | 完成判据 |
| --- | --- | --- |
| **0** | **三个未知项冒烟**（§1.3 A/B/C） | 三项各有明确结论，写回本文档 |
| 1 | 抽 `jellyfish-tui` 模块；parent `<modules>` 加一行；CLI 加依赖 | `mvn -q compile` 通过，空模块可跑 |
| 2 | `InflightTurn` + `ChatState` + `TuiReActListener`（纯逻辑，不动 UI） | 对应单测通过 |
| 3 | `ChatShell` + `TranscriptView` + `StatusBarView` + `ChatInputView`（版式成型） | 能起界面、能看到状态栏、输入框可打字 |
| 4 | `TuiApp` 装配 + `TuiRunMode` 真实现 + `Launcher.modeFor` 接线 | `jellyfish -tui` 能起 |
| 5 | 回合打通：`Enter` → `ChatShell` → 命令分流 → `chat().await()` → 原子追加 | **能完成一次完整对话** |
| 6 | 命令分流（`isCommand`）+ `/exit` 截胡 + 命令结果作系统消息 | `/help` 显示、`/exit` 退出 |
| 7 | 日志隔离（`log4j2-tui.xml` + 切换） | 界面无任何日志污染 |
| 8 | shade 打包 + fat jar 冒烟（§3.8） | fat jar 在真实 PTY 里跑通 |
| 9 | 文档同步（§4） | — |

**T2.1 的 `Esc` 中断**：`ReActTurn.cancel()` 已就绪，`run` 走 `await()` 时中断只需在按键回调里持有当前 `ReActTurn` 并调 `cancel()`。成本极低，**放在 T2.1 做**（T5 已明确要求中断要做）。

### T2.2 流式与细节

| 步骤 | 内容 |
| --- | --- |
| 1 | 流式：`TuiReActListener` 的增量真正驱动重绘 + 帧节流（§3.4） |
| 2 | 智能滚动的流式形态：`↓ 正在生成…` 提示条（§3.6） |
| 3 | 思考折叠块 |
| 4 | 工具轨迹的「进行中 → 完成/失败」原地更新 |
| 5 | Markdown 渲染（先按 §1.1 方法验 `tamboui-markdown` 的 Java 8 兼容性） |
| 6 | 输入框高度自适应（若 §1.3 未知项 B 成立） |

---

## 7. 验收标准

### 7.1 功能

1. `jellyfish -tui` 进入备用屏，显示版式（§2.3）：消息区 + 输入框 + 状态栏。
2. 输入文字 `Ctrl+S` 发送 → 完成一次完整回合 → 回答出现在消息区（角色前缀式）。
3. `Enter` 换行（输入框长高），不发送；`Ctrl+S` 发送。
4. 输入 `/help` → 命令结果作为系统消息显示；`/exit` → 干净退出并还原终端。
5. **命令与对话的分流判据只有 `CommandManager.isCommand`**（与 CLI 同一条）。
6. 每轮**现读** `sessionManager.current()`；`/new`、`/resume` 后立刻生效，**视图整体重建为新媒体**（T4）。
7. `Esc` 中断进行中的回合：出现 `⎿ 已中断`，输入框内容保留。
8. 回合结束后：Session 里的 assistant 消息**恰好一条**，且**与暂存区没有重复显示**。
9. 用户上翻历史后，新回合**不**把视图拽回底部，底部出现提示条。
10. `Ctrl+C` 退出，终端还原（备用屏退出、光标恢复）。
11. 退出码：正常 `0`；`-tui` **不再**返回 `5`。

### 7.2 环境与健壮性（PTY 实测，非管道）

12. **全程无日志污染界面**（`log4j2-tui.xml` 生效，stderr 干净）。
13. **fat jar**（`mvn package` 产物）在真实 PTY 里跑通，JLine 原生库正常加载（macOS `Mac/`）。
14. 终端尺寸极小（如 20×5）时**不崩溃**（版式崩坏可接受，报错不可接受）。
15. 退出后 `echo $?` 为 `0`，终端 `stty` 状态正常（无残留 raw mode）。

### 7.3 既有契约不回归

16. `mvn -q compile` 与全部既有单测通过。
17. `jellyfish -cli -p "/help"` 行为**一字不变**（stdout/stderr 契约、退出码）。
18. `jellyfish -server` 仍返回 `5`。

---

## 8. 已知限制与后续 TODO

| # | 限制 | 说明 |
| --- | --- | --- |
| L1 | TamboUI 是 **`0.5.0-SNAPSHOT`** 且官方标注 experimental | API 可能变动。**缓解**：把 TamboUI 用法收敛在 `ChatShell` / `*View` 少数几个类里，升级时改动面可控 |
| L2 | 官方文档示例是 Java 17 语法 | 见 §1.1；编码时只能对着 `javap` 写 |
| L3 | 不支持鼠标选中复制（备用屏 + raw mode） | 终端本身的限制，非本方案引入 |
| L4 | 长会话性能尚未实测 | 等 §1.3 未知项 A 结论；500 条消息下的帧率**暂不写入验收标准** |
| L5 | 会话持久化未落地 | 沿用 `cli方案.md` §8 L1；TUI 里同样「进程退出即丢」 |
| L6 | 无主题/配置段 | 不做 `tui.json`；颜色硬编码在 `*View` 里 |
| TODO-1 | 会话侧边栏 | `DockElement.left` 已预留（T8.1） |
| TODO-2 | Markdown 渲染 | T2.2 |
| TODO-3 | 权限 ASK 审批弹窗 | `DialogElement` / `StackElement` 能力已确认可用 |

---

## 9. 风险与缓解

| # | 风险 | 影响 | 缓解 |
| --- | --- | --- | --- |
| **R1** | §1.3 未知项 A（`LazyElement` 不跳过不可见子树）不成立 | **推翻 T8.3**，需要引入渲染缓存 → 与 T4 张力 | **T2.1 第一步就冒烟**；退路是「按可见窗口切片投影」，仍然不引入缓存 |
| **R2** | `TextAreaElement` 抢走 `Enter` / `Esc` | 发送与中断键失效 | §3.5 已在事件路由层显式截胡；T2.1 步骤 3 冒烟 |
| **R3** | shade 后 JLine 原生库或 services 失效 | fat jar 起不来或终端异常 | §3.8；**必须用 fat jar 而非 `exec:java` 验证** |
| **R4** | `react` 线程与渲染线程的数据竞争 | 偶发乱码 / 崩溃，极难复现 | §3.1 单一交接点 + volatile 脏标记；`TuiReActListenerTest` 专测并发 |
| **R5** | 流式期间 CPU 打满 | 笔记本风扇起飞 | T2.2 帧节流（§3.4）；T2.1 非流式天然无此问题 |
| **R6** | TamboUI snapshot 变更导致 API 断裂 | 编译失败 | L1 的收敛策略：用法集中在少数几个类 |
| **R7** | 日志污染界面在开发期漏检 | 用户看到花屏 | 验收 12 单独一条；`log4j2-tui.xml` 与 `log4j2.xml` 分文件，不靠条件逻辑 |

---

## 10. 裁决记录

### 10.1 本轮确认（T1～T8）

见 §0 表格。逐条来源：

- **T1** 用户：**「现在抽」**（否决「先放 `cli/mode` 跑通再抽」）。
- **T2** 用户：**「声明式」**（否决 `TuiRunner` 手写与立即模式）。
- **T3** 用户：**「ok」**（认可「TUI 版 `ReActListener` + 有界增量缓冲 + 帧节流 + `runOnRenderThread` 投递」）。
- **T4** 用户：**「我认可了」**（认可「视图 = 会话投影、不持有第二份消息列表」）。
- **T5** 用户：**「按推荐，中断要做」**。
- **T6** 用户：**「可以分两步走」**。
- **T7** 用户：**「markdown 第二步做」**。
- **T8** 用户：**「1.a；2.b；3.a；4.多行；5.B；6.b；退出/中断的视觉反馈按推荐的来」**。

### 10.2 我提出、用户未反对即采纳的（若不同意请指出）

| 项 | 内容 |
| --- | --- |
| **C1** | `RunMode` 实现留在 `jellyfish-cli`，`jellyfish-tui` 只放界面（避免循环依赖，§2.1） |
| **C2** | `jellyfish-tui` 不引入 Dagger（§2.2） |
| **C3** | 视觉语法两条规则、五个层级（§2.3） |
| **C4** | `InflightTurn` 有界（单回合文本 + 轨迹条数上限） |
| **C5** | 帧节流用「帧率上限」而非「token 去抖」（§3.4） |
| **C6** | 滚动跟随用「每帧回读 `isAtEnd()`」而非维护「用户意图」标志（§3.6） |
| **C7** | 日志切到文件用独立 `log4j2-tui.xml` + `log4j.configurationFile` 系统属性（§3.7） |
| **C8** | 单测只覆盖与 TamboUI 无耦合的逻辑，渲染交 PTY 端到端（§5） |
| **C9** | `Esc` 中断放在 T2.1 而非 T2.2（`cancel()` 已就绪，成本极低） |
| **C10** | `TuiReActListener` 不需要 `flushTrace()` 式的中间轮次转写（§3.2） |

### 10.3 未知项冒烟结论（T2.1 步骤 0，已完成）

| 未知项 | 结论 |
| --- | --- |
| A：`lazy()` 是否跳过不可见子树 | ❌ **否定**；真正的瓶颈是「一次布局的子元素个数」，120～180 处断崖。详见 §1.4 |
| B：`TextAreaElement` 高度自适应 | ✅ **成立**（5 行文本占 5 行） |
| C：无事件时是否重绘 / `runLater` 语义 | ✅ 持续重绘 ~26fps；`runLater` 与 `runOnRenderThread` 均执行在渲染线程（`main`） |

### 10.4 T8.3 修正 —— **✅ 已裁决（用户批准）**

冒烟结果推翻了原始的 T8.3（「每帧全量重建 + `LazyElement`」）。

**问题**：

| 原裁决项 | 冒烟事实 | 结论 |
| --- | --- | --- |
| T8.3「每帧全量重建 + `LazyElement` 兜住不可见的」 | `lazy()` 零跳过效果；且子元素数一旦过百，布局直接断崖（>3000ms/帧） | **不可行** |
| T8.5「智能跟随用 `scrollbarState.isAtEnd()`」 | `ScrollableElement` 的 state 每帧归零、`add()` 无 id 替换 | **依赖不成立** |

**裁决结果**：

> 消息区不再用「N 个子元素 + 滚动容器」，改为 **每帧把会话投影成单个 `RichText`，并自己做换行与切片**：
>
> ```
> ChatState.scrollOffset（自己的视觉行偏移）
>         ↓
> project(messages, inflight, width) → List<VisualLine>   ← 自己按显示宽度换行，1 VisualLine = 1 视觉行
>         ↓ slice [offset, offset + viewportRows)
> richText(Text.from(window))                             ← 一个元素，约 24 行
> ```
>
> **收益**：① 单元素，彻底避开子元素断崖（实测 3000 行全量可承受，切片后更优）；② 滚动位置成为自己的字段，T8.5 智能跟随变成平凡；③ 不依赖未验证的框架滚动语义；④ 投影成为纯函数，§5 的 `ChatStateTest` / `TranscriptViewTest` 真正可测；⑤ **完全契合 T8.2「角色前缀 + 缩进」纯文本语法**。
>
> **代价与配套约定**：① 自实现 CJK 感知的显示宽度与换行（T8.9）；② 每帧投影是 O(总行数)，**投影行数必须设上限**（T8.8）。
>
> **连带修正**：T8.5 的判据从 `ScrollbarState.isAtEnd()` 改为我们自己的 `scrollOffset >= totalRows - viewportRows`，**不再使用 `ScrollableElement`**。

### 10.5 冒烟发现的框架坑（写进编码约定）

1. **不要用 `Toolkit.scrollable(Element...)`** —— 该构造形式不渲染内容（只剩滚动条），必须 `scrollable().add(...)`；本方案已不用 `ScrollableElement`。
2. **不要传 `null` 给 `Span.styled`** —— `Span.computeHashCode` 会 NPE。无样式用 `Span.raw(...)`，要样式用 `Style.EMPTY.xxx()`。
3. **不要用 `var` / switch 表达式** —— 官方示例是 Java 17 语法，本仓库是 Java 8。
4. **PTY 探针必须 `ioctl(TIOCSWINSZ)`** —— 否则尺寸为 0，什么都不画。

---

### 10.6 键位反转 —— **✅ 已裁决（用户批准）**

T2.1 端到端冒烟时发现原 T5 键位**不可实现**，停下来求证后用户裁决：**采用反转方案**、**不接受鼠标捕获**、**日志改绝对路径**。

**促成反转的实测证据**（按键转储探针，直接打印框架解码结果）：

| 发送序列 | 框架解码 | 说明 |
| --- | --- | --- |
| `\r` | `ENTER` alt=false shift=false | 裸回车 |
| `\x1b\r`（`Alt+Enter`） | **`UNKNOWN`** | 修饰信息丢失 |
| `\e[13;2u`（CSI-u `Shift+Enter`） | **`UNKNOWN`** | 同上 |
| `\e[27;2;13~`（CSI-27） | **`UNKNOWN`** | 同上 |
| `\n` | `ENTER` alt=false shift=false | **与裸回车无法区分** |
| `\r\n` | `ENTER` ×2 | 会变成两个动作 |
| `\x03`（`Ctrl+C`） | `CHAR` ctrl=true cp=99 | `Ctrl+字母` 一律这种形状 |

结论：`hasShift()` / `hasAlt()` **永远是 false**，不存在任何可用的「带修饰的 Enter」。故：

| # | 项 | 裁决 |
| --- | --- | --- |
| **R1** | 括号粘贴 | **开**（`TuiConfig.bracketedPaste(true)`）。默认 `false` 时粘贴内容的每个换行都会被当成 `ENTER`，一次粘贴会被拆成多次提交。实测开启后应用发出 `\e[?2004h`，`PasteEvent` 一次送达完整多行文本 |
| **R2** | 鼠标捕获 | ~~不开~~ → **T2.2 修订：默认开**（`mouseCapture(true)`，`mouseMotion` 仍 `false`），配 `-Djellyfish.tui.mouseCapture=false` 逃生门。理由：关着时滚轮到不了应用，滚轮滚动成为不可能的缺口；开启后非滚轮鼠标事件由外壳吞掉以保住焦点。代价：终端选择/复制需按住修饰键（macOS 为 Option）。详见 §11.9 |
| **R3** | `Ctrl+C` | **补上退出**。此前实现里根本没有它，而 raw 模式关掉了 `ISIG`（不会有 `SIGINT`），按键会落到 `insertChar` **在输入框里插一个 `c`**。另加护栏：`insertChar` 拒绝一切带 `Ctrl` 的字符 |
| **R4** | 换行 / 发送 | **反转**：`Enter` 换行、`Ctrl+S` 发送。实测 `Ctrl+S` 解码为 `CHAR` ctrl=true cp=115，且框架进 raw 模式时已关掉 `IXON`，**不会被 XOFF 流控吞掉** |
| **R5** | 日志路径 | 默认改**绝对路径** `${sys:user.home}/jellyfish/jellyfish-tui.log`。原先的相对路径按进程 CWD 解析，在项目目录里启动会往项目里丢日志（有被误提交的风险），在只读目录里启动则静默写失败 |

**顺带纠正一处我写错的注释**：`ensureLogDirectory()` 曾以「Log4j2 不建父目录」为由预先建目录，实测**它会自建多级父目录**（`-Djellyfish.log.file=/tmp/a/b/c.log` 能把 `a/b` 一并建出来），因此该方法已删除，不留无用代码与不成立的注释。

### 10.7 终端前置条件 —— **✅ 已裁决（用户批准）**

**问题**：没有可交互终端时 TUI **不报错，而是永久挂住**。实测 `java -jar ... -tui < /dev/null`：JLine 检测不到终端，只往 stderr 打一行 `Unable to create a system terminal`（容易被忽略），随后退化成 dumb 终端；TamboUI 照常进事件循环，`jstack` 停在 `TuiRunner.pollEvent` 等一个永远不会来的事件。用户看到的是「黑屏 + 不退出」，无从判断是环境不对还是自己代码卡死。

**判据**：`System.console() == null`。两种情况下的取值实测对得上：

| 环境 | `System.console()` |
| --- | --- |
| 真实终端 / PTY | `java.io.Console@...`（非 null） |
| 管道 / 无终端（即现在挂住的那种） | `null` |

**已知边界（已确认接受）**：JDK 8 的 `System.console()` 语义是「`stdin` **或** `stdout` 任一被重定向就返回 `null`」（JDK 22 之后才改成只看是否 tty），因此 `-tui > log.txt` 也会被拦下。TUI 本来两个流都要用，拦住是对的。

**裁决**：在 `RunMode` 上加 `checkEnvironment(options)`（默认 `Optional.empty()`，Java 8 default 方法），由 `TuiRunMode` 覆写，`Launcher` 在**启动内核之前**调用；不满足则报错并返回 `STARTUP_ERROR(3)`。退出码取 3 而非 4：它发生在内核启动之前，性质是「启动条件不具备」，与「配置写错」同类，脚本都应当直接放弃。

**保留一个显式逃生门**：`-Djellyfish.tui.skipTerminalCheck=true` 跳过检查。理由是判据可能误伤异常终端或 IDE 的伪终端实现，而误伤的代价是「完全用不了」；显式开关让用户自己担责，与「默默挂住」有本质区别。

**为什么放在 `Launcher` 而不是模式的 `run()` 里**：放进 `run()` 时插件扫描、事件线程、HTTP 客户端池都已经白起过一遍。

## 11. 落地记录（T2.1 骨架完成）

### 11.1 交付内容

| 项 | 结果 |
| --- | --- |
| `jellyfish-tui` 模块 | 已抽，parent `<modules>` 与 `jellyfish-cli` 依赖已接 |
| 界面 | `TuiApp` / `ChatShell` / `ChatInputView` / `StatusBarView` / `ShellCommand` / `InputAction` / `InputKeyMapper` |
| 视图 | `ChatState` / `InflightTurn` / `TranscriptProjector` / `text/{DisplayWidth,StyledSegment,VisualLine,LineWrapper}` |
| 监听器 | `TuiReActListener` |
| CLI 侧 | `TuiRunMode` 真实现、`Launcher.modeFor` 完整接线、`PlaceholderRunMode` 仅剩 Server 使用 |
| 日志隔离 | `log4j2-tui.xml` + `JellyfishApplication.applyLogTarget` |
| 单测 | `jellyfish-tui` 121 个，全仓库 319 个，全绿 |
| fat jar 冒烟 | 见 §11.4 |

### 11.2 落地时对方案的五处修正（均由实测驱动）

| # | 方案原定 | 实际落地 | 原因 |
| --- | --- | --- | --- |
| **D1** | 用 `TextAreaElement` + 全局处理器截胡 `Enter` | **自实现 `ChatInputView implements Element`**，直接渲染 `TextArea` 部件 | 三条路全堵死：全局处理器排在聚焦元素**之后**；按键是**冒泡**的，父级装饰器无法抢先；`StyledElement.onKeyEvent` 对 `TextAreaElement` 是**死钩子**（重写 `handleKeyEvent` 时不调 `super`）。何况 `TextAreaElement` 把所有 `Enter` 当换行，**根本区分不出** `Shift+Enter`——T5 的键位它做不到 |
| **D2** | 工具轨迹存入暂存区 | **不进暂存区**，直接由会话投影得出 | `onToolCallStarted` 发生在 assistant 消息落库**之后**，`assistant.toolCalls` 与 `tool` 消息都已在会话里。这比原方案更守 T4 |
| **D3** | （未预见于方案） | **新增外壳提示缓冲**（有界 50 条）承载命令结果。T2.2 又把它从 `ChatState` 的私有内部类上提为顶层 `ShellNotice`（带时间戳） | 命令输出不是会话消息，塞进会话会污染发给模型的历史；它由外壳持有。原先「附在投影之后」会造成命令输出永远贴在屏幕底部且排在更晚的对话之前，T2.2 改为按时间戳归并进投影（§11.9） |
| **D4** | （未预见于方案） | **`InflightTurn.Outcome` 增加 `IDLE` 开场态** | 原本初始态是 `RUNNING`，导致**用户还没发第一条消息时屏幕就显示假的「处理中…」**——单测直接抓到了这个 UX bug |
| **D5** | 「↓ N 条新消息」提示放消息区 | **放状态栏** | 消息区行数已被投影切片占满，额外加一行会把最新一行挤出可视区——而跟随底部时用户最想看的正是那一行 |

另有两条实现细节值得记下：`TextAreaState` 其实**有 `clear()` / `setText()`**（原先以为要换实例）；`END` 键归消息区「跳到底部」，因此编辑器的「光标到行尾」在本轮**不可用**（T2.2 可考虑改用 `Ctrl+E`）。

### 11.3 测试补充（超出 §5 的部分）

| 测试类 | 覆盖点 |
| --- | --- |
| `text/DisplayWidthTest` | 窄 / 宽 / 零宽码点、CJK 与全角标点、**投影器实际使用的前缀宽度**（前缀宽度算错会让整屏错位） |
| `text/LineWrapperTest` | 换行、续行缩进、中文按 2 列、强制换行、行首空格丢弃、同样式段合并、**宽度退化时不抛异常** |
| `InflightTurnTest` | 有界截断（保留尾部）、`begin` 重置、清空时机、**并发读写不丢增量**（4 线程 × 500 次） |
| `TranscriptProjectorTest` | 全部视觉层级、表头共用、轨迹折叠、四种终局提示、消息上限折叠、**system 角色不进投影** |
| `ChatStateTest` | 窗口切片、跟随进出、**用户上翻后不被新内容拽回底部**、流式提示文案、会话切换即时反映 |
| `TuiReActListenerTest` | 7 个回调落点、`onToolCallStarted` 只清文本、**典型回合全程不重复显示** |

另：既有 `LauncherTest` 里两条断言「TUI 是占位」的用例已随实现更新；一条「TUI 在无 TTY 下退 4」的用例被**删除**——它会真的进事件循环并阻塞构建，这条路只能靠 PTY 端到端验证（§5 已定「渲染交 PTY」）。

### 11.4 fat jar 端到端冒烟（真实 PTY，非管道）

| 验收项 | 结果 |
| --- | --- |
| 打包完整性 | tamboui 681 条、jline 600 条、`META-INF/services/dev.tamboui.terminal.BackendProvider`、`org/jline/terminal/provider/*`、`org/jline/nativ/**`、`META-INF/versions/**`（59 条）**全部保留** |
| 进备用屏 | ✅ `\e[?1049h` |
| 出备用屏 + 光标恢复 | ✅ `\e[?1049l`、`\e[?25h` |
| 版式 | ✅ 消息区圆角边框 + `jellyfish · 会话` 标题、输入框 + placeholder、状态栏 `coder · 默认 · normal · 0` |
| `Enter` 发送 | ✅ `/help` 提交后输入框清空 |
| 命令结果渲染 | ✅ `/help` 的 10 条命令以提示行显示；列表里**没有 `/exit`**（外壳自有，不注册） |
| `/exit` 退出 | ✅ 退出码 `0` |
| 日志不污染界面 | ✅ 全程唯一日志（PF4J `No 'plugins' root` WARN）写进了 `jellyfish-tui.log`，屏幕无杂质 |
| `-cli` 契约未回归 | ✅ `echo "/help" \| ... -cli` 输出与退出码不变 |
| `-server` 仍退 5 | ✅ 且提示文案已带上 `-tui` |

### 11.5 T2.1 未做（转 T2.2）

流式增量刷新（当前回合走 `await()` 后原子追加）、思考折叠块、工具轨迹的「进行中 → 完成/失败」原地更新、Markdown 渲染、输入框高度自适应到 6 行的**换行计数**（当前按逻辑行数计）、编辑器 `END` 键、`Ctrl+E` 折中方案。

### 11.6 第二轮：键位反转后的验证

§10.6 的五项修正落地后重新打包冒烟，三项独立 PTY 会话：

| 用例 | 发送 | 期望 | 结果 |
| --- | --- | --- | --- |
| A | `ab` + `Enter` + `cd` | `Enter` 换行，输入框变两行，**不提交** | ✅ 屏幕第二行区显示 `ab` / `cd` |
| B | `/help` + `Ctrl+S` | 命令执行、输入框清空 | ✅ `/help` 的 10 条命令渲染为提示行 |
| C | 括号粘贴 `/help\n/status` | 整段作为文本插入，**不执行任何命令** | ✅ 输入框显示两行，无命令输出 |

三次会话均用 **`Ctrl+C` 退出，退出码全为 `0`**；`\e[?2004h`（括号粘贴）已发出；`\e[?1000h`（鼠标捕获）**未**发出，与当时 R2 一致（T2.2 后默认发出，见 §11.9）。

日志改为绝对路径后复核：写入 `~/jellyfish/jellyfish-tui.log`，`/tmp/jellyfish-tui.log` 不再被更新（CWD 不再被污染）；且 `-Djellyfish.log.file=...` 指向不存在的多级目录时 **Log4j2 自建成功**。

新增 `InputKeyMapperTest`（14 个用例）锁住键位判定：`Ctrl+S`→发送、`Ctrl+C`→退出、`Esc`→中断、**`Enter`/`Shift+Enter`/`Alt+Enter` 一律不得判成发送**（防回归成「误把 Enter 当发送」；后两者现判为补全接受，面板没弹时仍由外壳放行当换行）、`Ctrl+非字符键`与`无 Ctrl 的同名字母`不得命中。

### 11.8 终端前置条件检查的验证

| 场景 | 结果 |
| --- | --- |
| 无 TTY（`< /dev/null`） | ✅ **1 秒**内报出可执行文案并退 `3`（修复前：永久挂住，`jstack` 停在 `pollEvent`） |
| 逃生门 `-Djellyfish.tui.skipTerminalCheck=true` | ✅ 跳过检查，恢复到原来的挂住行为（证明开关真的生效，不是摆设） |
| 真实 PTY | ✅ 进备用屏、`/help` 正常执行、**未被误拦** |

`LauncherTest` 补两条用例：无终端时返回 `STARTUP_ERROR` 且 `bootstrap` **从未被调用**（锁住「自检先于启动内核」）；占位模式仍先返回 `5`（锁住判定顺序，防止将来把自检提到 `isImplemented` 之前）。`TuiTerminalTest` 5 条用例：逃生门开／关语义、文案必须同时包含 `-cli` 与开关名（文案是用户唯一的线索，必须能照着做）。依赖运行环境的断言用 `assumeTrue` 保护，在可交互终端里跑测试时跳过而不是误报。

### 11.7 仍然存在的已知限制

- **`\r\n` 会变成两个换行**：两者都被解码成同一种 `ENTER`，框架层面无从区分。多数终端在 raw 模式下 `Enter` 只发 `\r`，因此日常不触发；靠粘贴走 `PasteEvent` 也不受影响。
- **终端选择/复制需按住修饰键**（T2.2 开鼠标捕获的代价，可用逃生门退回；见 §11.9）。
- **编辑器 `End` 不可用**：被消息区「跳到底部」占用。
- `Ctrl+S` 与终端的「保存」直觉不同，靠输入框 placeholder 常驻提示（`Enter 换行·Ctrl+S 发送·Esc 中断`）补偿。

### 11.9 T2.2 修复：滚轮滚动、命令输出堆积与命令输出样式（**均经用户裁决**）

两处修复都不改内核，只落在外壳（`jellyfish-tui`）。裁决依据：滚轮取**方案 A**（开鼠标捕获 + 逃生门），命令输出取**按时间戳归并进投影**。

#### 问题 1：滚轮不能向上找历史

**根因**：滚轮事件属于鼠标捕获，`mouseCapture` 关着时它**根本到不了应用**。未捕获时不少终端会把备用屏下的滚轮翻译成 `↑`/`↓`，而这两个键在 `InputKeyMapper` 里是补全导航，面板没弹时落回输入框——单行输入上光标无处可移，表现就是「滚轮毫无反应」。

**落点**：

| 位置 | 改动 |
| --- | --- |
| `TuiApp.configure()` | `mouseCapture(mouseCaptureEnabled())`，`mouseMotion` 仍为 `false`（无悬停 / 拖动语义） |
| `TuiApp.MOUSE_CAPTURE_PROPERTY` | 逃生门 `jellyfish.tui.mouseCapture`，只有显式 `false` 才关闭（默认开） |
| `MouseScrollMapper` | 新增，纯函数：`SCROLL_UP`/`SCROLL_DOWN` → 滚动动作，其余事件返回空 |
| `TuiApp.ScrollFallback` | 鼠标分支：滚轮滚消息区；**其余鼠标事件一律吞掉** |
| `InputAction` / `TuiApp.applyScroll` | 新增 `SCROLL_UP`/`SCROLL_DOWN`（步长 3 行），键盘与鼠标两条路径共用同一个应用点 |

**为什么非滚轮事件要吞掉而不是放行**（读框架字节码得到）：`EventRouter.routeMouseEvent` 对「点在没有元素命中的位置」的处理是 `focusManager.clearFocus()`。消息区不可聚焦，点一下它就会丢掉输入框焦点，下一帧才由 `ToolkitRunner` 重新挑回第一个可聚焦元素。输入框是唯一的可聚焦元素、鼠标本来也做不了别的事，因此吞掉，把「焦点常驻输入框」（T8.6）变成确定性行为。将来若要支持点击元素，需要在这里放行非滚轮事件并同时接受上述焦点语义。

**PTY 实测**（fat jar，`script` 分配 PTY）：

| 场景 | 结果 |
| --- | --- |
| 默认启动 | ✅ 发出 `\e[?1000h` / `\e[?1002h` / `\e[?1006h`（鼠标捕获与 SGR 扩展），退出时发出 `\e[?1000l` |
| 喂入 SGR 滚轮序列 `\e[<64;10;5M` ×2 + `\e[<65;10;5M` | ✅ 不崩、不卡，`Ctrl+C` 退出码 `0` |
| `-Djellyfish.tui.mouseCapture=false` | ✅ 只发 `\e[?2004h`（括号粘贴），**不**发 `?1000h`，逃生门生效 |

**测试**：`MouseScrollMapperTest` 5 条（上下滚轮命中、左右滚动与按下/抬起/移动必须判空、步长锁定）、`TuiAppTest` 4 条（逃生门默认开、显式 false、大小写不敏感、取值非 false 仍开）。

#### 问题 2：命令输出在尾部堆积

**根因**：`ChatState` 的外壳提示缓冲（D3）是**独立缓冲、附在整份会话投影之后**，且不带时间戳。于是命令输出永远贴在屏幕底部（后发生的对话反而排在它上面），多条命令输出不断追加直到上限 50 条，且只在切会话时清空。

**落点**：

| 位置 | 改动 |
| --- | --- |
| `ShellNotice` | 新增顶层不可变类：`timestamp` + `text` + `error`；`appendNotice` 时取 `System.currentTimeMillis()` |
| `TranscriptProjector.project` | 签名增加 `notices`；双指针按时间戳归并（**同毫秒时消息在前**——提示是对刚发生的事的反馈）；**比投影窗口更旧的提示直接丢弃**（它的位置在「已折叠」行之前） |
| `ChatState.refreshProjection` | 删掉「投影后整体拼接」，提示直接进投影；`MAX_NOTICES=50` 仍然生效 |

**代价与边界**：提示仍在 `ChatState` 里保留最多 50 条（不无界增长），但不再全部渲染；投影仍是纯函数（时间戳由入参带入）。

**测试**：`TranscriptProjectorTest` +5 条（按时间戳插入、晚于最后一条消息排在其后、同毫秒消息在前、窗口外丢弃、窗口内保留）、`ChatStateTest` +2 条（命令输出插在两条消息之间且不在末尾、提示条数封顶）。

#### 本轮验证汇总

`jellyfish-tui` 189 条、全仓库 1128 条单测全绿；`mvn package -DskipTests` 出 fat jar 后在真实 PTY 完成上述冒烟。

#### 问题 3（外观）：命令输出与工具轨迹长得一样

**抱怨**：命令的输出有点难看。查下来不是「不好看」而是**四条具体的错**：

| 现象 | 根因 |
| --- | --- |
| 一摞 `⎿` 箭头 | `notice()` 按 `\n` 拆行、**每行**都套一次 `TRACE_PREFIX` |
| 看不出是「谁说的话」 | 复用了工具轨迹的 `⎿ ` + `dim`，两者在屏幕上完全同形 |
| 读起来费眼 | `dim` 是给「不想细读的轨迹」用的，命令输出却是用户主动要的结果 |
| 敲错命令看不出来 | 只有「是不是 error」一个布尔位，`UNKNOWN` 与 `OK` 同款 |

**方案（用户裁决：按推荐方案）**：把提示做成**块**，并给三态各自的记号。

| 项 | 做法 |
| --- | --- |
| 命令回显 | 块首一行 `❯ /help`（用户消息同形，青色前缀）——滚动历史里能直接看出这是哪条命令的输出 |
| 悬挂缩进 | 只有块首行带 `    ⎿ `（6 列），续行 6 列纯缩进；**不再逐行重复箭头** |
| 保留原始缩进 | `/help` 自带的 2 空格（列表层级）保留 → 列表落在第 8 列。实现要点：`LineWrapper` 会丢弃正文行首空格，所以原始行首空格要**并入 prefix**，不能塞进正文 |
| 去 dim | 正文用正文色；`dim` 只留给工具轨迹与折叠提示 |
| 三态 | `INFO`→`⎿ ` 默认色，`WARN`（未知命令）→`! ` 黄，`ERROR`→`✗ ` 红。`CommandResult.Kind` 到 `ShellNotice.Kind` 的映射写在 `TuiApp.kindOf` |
| 块前空行 | 多条命令连续执行时不再糊成一片 |

**落点**：`ShellNotice` 增 `command` 字段并把 `boolean error` 换成 `Kind`；`ChatState.appendNotice` 改成 `(command, text, kind)` 并对无命令的反馈提供二参重载；`TranscriptProjector.notice(ShellNotice, int)` 取代原来的按行前缀版本，新增 `SHELL_COMMAND_PREFIX` / `SHELL_PREFIX` / `SHELL_WARN_PREFIX` / `SHELL_ERROR_PREFIX` / `SHELL_INDENT` 五个常量（三种块前缀与续行缩进**实测等宽 6 列**，否则悬挂缩进会错位）。

**PTY 实测**（`ioctl(TIOCSWINSZ)` 设 80×24 后跑 fat jar，用极简 VT 模拟器重建屏幕；探针里的杂字是模拟器不处理宽字符覆写的假象，层级本身是准的）：

```
│  ❯ /help
│    ⎿ 可用命令（10 条）：
│        /agent [agentId]              查看或切换 agent（别名：/a）
│        /help [命令]                  显示命令帮助（别名：/h、/?）
│        /status                       显示当前会话概要
│
│  ❯ /foo
│    ! 未知命令：/foo（输入 /help 查看可用命令）
```

**测试**：`TranscriptProjectorTest` +4 条（多行只首行带前缀且保留原始缩进、命令回显、三态前缀、长行自动换行的续行与块前缀等宽）并改写 5 条归并用例；`ChatStateTest` 2 条改用新签名（含「命令原文应回显」断言）。

**顺带修好的一件事**：`CommandResult.Kind.UNKNOWN` 以前落进「非 error」分支，敲错命令的提示与正常输出一模一样；现在它是独立的警示态。
