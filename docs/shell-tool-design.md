# shell 命令执行工具 · 技术设计文档

- 状态：已评审；**批次 1 已落地**，批次 2~4 实施中（见 §8）
- 版本：0.0.1-SNAPSHOT 对应
- 范围：内核（`jellyfish-api` / `jellyfish-infra` / `jellyfish-core` / `jellyfish-tui` / `jellyfish-cli`）与新增插件 `jellyfish-plugin-shell`

本文只写**已定方案**。推导过程、被否决的备选与各自主流实现的对照见文末「设计依据」。

---

## 1. 背景与目标

现在官方插件里只有文件类工具（`read_file` / `write_file` / `edit_file` / `list_dir` / `grep_files`），没有执行命令的能力。这限制了 agent 做真正的工作：跑构建、跑测试、查 git 状态、调用项目自己的脚本。

本期要交付两件事：

1. **一个新插件 `jellyfish-plugin-shell`**，提供一个 `shell` 工具，在受控条件下执行命令行命令。
2. **内核补齐「长时间工具」的基础设施**：调用级取消、内存有界的输出捕获、被截断内容的可靠落盘与回查。这三件事今天都不存在，而它们是 shell 能不能安全、可用地存在的前提。

第 2 件事是第 1 件事的前置条件，不是顺带优化：没有取消，Esc 杀不掉在跑的命令；没有捕获期的内存上界，`yes` 能把 JVM 撑爆；没有落盘，命令输出被截断后就永久消失（不像文件还能按 `offset` 重读）。

### 1.1 明确不做

| 不做 | 原因 |
| --- | --- |
| 沙箱 / 权限降级 / 容器内执行 | 工具与 jellyfish 进程同权限。要硬隔离就把整个 jellyfish 跑在容器里，这是**唯一的**硬边界 |
| 命令字符串黑名单与「解析式安全」 | 拦不住 `$(...)`、编码绕过、`./`，只会制造「有防线」的错觉 |
| 目录围栏（把 shell 限制在项目目录） | 可被 `cd /`、绝对路径、`python3 -c` 任意绕过；且与 `read_file`/`write_file` 不自洽——那两者本来就能读写任意路径。单独关 shell 只是把破坏力挪到别的工具 |
| 后台进程 / 常驻服务 / `shell_kill` | 需要会话级进程注册表、输出重定向 API、会话关闭清理、TUI 展示，是另一个量级的能力，另开一期 |
| 会话级工作目录（内核概念） | 牵动 `Session` 快照、持久化、恢复兼容、所有工具的路径解析，收益不足；v1 用 `cwd` 参数 |
| 落盘文件的引用计数式保留 | 会让 `ToolOutputStore` 与会话历史耦合；只写清「保留窗口内有效」这条边界 |
| TUI 审批的「本次会话记住该决定」 | 命令分类器（§4.8.9）已消掉主要痛点 |

---

## 2. 现状约束

以下都是代码里已经成立的事实，它们框定了设计空间。

| 事实 | 位置 | 对设计的约束 |
| --- | --- | --- |
| 工具同步执行、跑在 `react` 池线程上，同一轮内串行 | `ReActLooper.loop` / `executeTool` | 一次 shell 调用独占该回合；**取消只能靠协作式信号** |
| 同步派发没有超时，取消只在「每轮开始」「每个工具之前」检查 | `ReActLooper` 第 230~260 行 | 工具必须自带截止时间；内核能做的是**把取消信号送到工具手里** |
| 工具输出是一个字符串，之后由 `ToolOutputLimiter` 统一截断并落盘 | `ReActLooper.executeTool` | 没有流式通道；`ReActListener` 需要新增回调 |
| 权限只按**工具名**判定 | `PermissionManager` / `PermissionPolicy` | 命令级策略只能由插件实现，而插件侧只有两态否决 |
| 审批是单槽位、fail-closed：无审批者 = 拒绝 | `ApprovalChannel` | 只有 `-tui` 有审批浮层；`-cli` / `-server` 下 `askTools: ["shell"]` 等价于禁用 |
| `readOnly` 由提供方声明，PLAN 白名单只认它；用户配置只能追加 | `ToolDescriptor` / `ReadOnlyTools` | `shell` 必须声明 `readOnly=false` |
| 工具插件只依赖 `jellyfish-api`（provided） | `jellyfish-plugin-tools/pom.xml` | shell 插件要引入 commons-exec，必须 shade 进插件包 |
| 工具输出会被送到远端 LLM | 架构本身 | 环境变量、命令输出里的密钥一旦出现就是外泄 |
| 插件只能注册回调，不能发起调用；拿不到工作目录 | `JellyfishPlugin` / `PluginContext` | 落盘能力必须由内核提供，不能让插件自己写文件 |
| `InflightTurn` 的方法已经是 `synchronized`，另有 `volatile` 脏标记 | `jellyfish-tui/InflightTurn` | 实时输出可以在非 react 线程写入暂存区（见 §4.9） |

---

## 3. 总体设计

### 3.1 三条不变式

这三条贯穿全文，实施时任何取舍都不得违反。

**I1 · 不许静默丢内容。**
丢弃只允许发生在两处，且都必须留下痕迹：

- **内核截断**：落盘完整内容 + 信封给出路径与回查指引（有恢复路径）；
- **工具兜底上界**：在输出里显式声明「这部分未捕获、不可恢复」（有显式告知）。

不允许出现第三种：内容少了，而模型和用户都不知道。

**I2 · 两个触发点，一份实现。**
「丢内容的截断」不再是唯一一处，但**预览切分、信封渲染、落盘写入这三件事的实现只有一份**：

- 触发点 A：事后截断——工具返回对象，`ToolOutputLimiter.limit` 发现超限；
- 触发点 B：捕获期溢出——`ToolOutputSink` 在写入过程中发现超出预算。

两者共用同一套 `ToolOutputPreview`（头尾切分）+ `ToolOutputEnvelope`（信封）+ `ToolOutputStore`（落盘）。**不得各写一遍**，否则两份阈值、两种省略标记、两种路径必然漂移。

**I3 · 显示通道可丢，捕获通道不可丢。**
实时输出（给外壳看）是旁路，允许丢弃（队列满、界面来不及画）；捕获（落盘 + 回灌模型）不允许丢。两者绝不能耦合——显示侧一阻塞就会卡住 capture，进而卡住正在写管道的子进程。

### 3.2 一次工具调用的新时序

```
ReActLooper.executeTool(session, toolCall, listener)
  │
  ├─ 1. 造取消令牌 CancellationToken（由 ReActTurn 提供）
  ├─ 2. 造输出捕获 sink：ToolOutputLimiter.sink(sessionId, toolCallId, toolName, tee)
  │        └─ tee = chunk -> listener.onToolCallOutput(toolCallId, toolName, chunk)   ← 旁路，可丢
  ├─ 3. new ToolCallRequest(toolName, args, sessionId, cancellationToken, sink)
  ├─ 4. permissionManager.decide(...)                                  ← 不变（含新增的插件三态）
  ├─ 5. extensions.invoke(handler, request)                            ← 不变
  │        └─ shell 工具内部：
  │             每条 stdout/stderr 泵线程 → sink.write(chunk)
  │             sink.summary("cwd: … · exit: 0 · 耗时: 1.2s")
  │             return new ToolCallResult("shell", sink.finish())
  ├─ 6. finally { sink.finish(); }                                     ← 幂等兜底，保证句柄释放
  ├─ 7. outputLimiter.limit(...)                                       ← 不变；此时通常已短，直接穿透
  ├─ 8. appendMessage(tool 消息) / listener.onToolCallCompleted(...)    ← 不变
  └─ 9. flush（不变）
```

关键点：**第 7 步保留不动**。走了捕获路径的工具返回的是一段已封好在预算内的文本，`limit` 对它只是穿透；没走捕获路径的工具（现有 5 个文件工具、脚本插件）行为完全不变。这样内核不需要为「两种工具」分支。

---

## 4. 详细设计

### 4.1 api 变更（一次冻结）

四处改动必须一次性合入，分两次做第二次就是破坏性变更。

#### 4.1.1 新增 `CancellationToken`

```java
package zcd.jellyfish.api.extension;

public interface CancellationToken {

    /** 永不取消的令牌，供进程级 / 测试调用点使用。 */
    CancellationToken NONE = new CancellationToken() { ... };

    /**
     * 是否已被取消。
     * <p>拉模型：调用点在阻塞前或循环中自查。
     */
    boolean isCancelled();

    /**
     * 注册取消回调；若注册时已取消，立即执行。
     * <p><b>回调可能在渲染线程上执行</b>（TUI 的 Esc 路径），因此实现必须快、不得阻塞、
     * 不得等待子进程退出；只允许「发信号」这类非阻塞动作。
     */
    void onCancel(Runnable callback);
}
```

**为什么不能用事件做取消**：`EventChannel` 是异步、有界、**明确可丢**的通道。拿它承载取消，等于「偶尔取消不掉」，而取消是用户按 Esc 后必须成立的同步事实。用可丢通道承载不可丢语义，是这套设计里最容易顺手做错的地方。

**为什么不是「内核给工具调用加统一超时」**：同步派发没有安全的中断方式——`Thread.interrupt` 不会中断 `Process.getInputStream().read()`，`Thread.stop` 已废弃。内核能做且应该做的只有把信号送到工具手里，超时由工具自己实现。

#### 4.1.2 新增 `ToolOutputSink`

```java
package zcd.jellyfish.api.extension;

public interface ToolOutputSink {

    /** 不用捕获的工具拿到的实例：吞掉全部写入，finish 返回 null。 */
    ToolOutputSink NOOP = new ToolOutputSink() { ... };

    /**
     * 追加一段输出。
     * <p><b>必须线程安全</b>：commons-exec 分别泵 stdout 与 stderr，两条线程会并发调用。
     */
    void write(String chunk);

    /**
     * 声明一条「始终可见」的元数据行（工作目录、退出码、耗时……）。
     * <p>渲染在正文首行，因此头尾切分时必被保留。可多次调用，按调用顺序拼接。
     */
    void summary(String text);

    /**
     * 结束捕获并返回最终文本，幂等。
     *
     * @return 最终文本；未捕获任何内容时返回 {@code null}
     */
    String finish();
}
```

**为什么 sink 由内核实现而不是工具自己写文件**：插件只依赖 `jellyfish-api`，拿不到 `ToolOutputStore`。若让插件自己落盘，就会出现第二套目录、命名、清理与提示格式——直接违反「禁止两处各写一遍键名」。sink 由内核实现后，**插件看不到路径、目录、命名、清理、信封格式**，它只知道「往里写」。

**为什么落盘只在溢出时发生**：小输出（`ls`、`git status`）全程在内存里结束，不产生任何文件。这一点与 Claude Code 不同——它总是写工作文件，因为它要支持「运行中读回」；我们不需要那个能力，因此省掉每次调用一次文件系统写入。

#### 4.1.3 `ToolCallRequest` 增加两个字段

```java
public ToolCallRequest(String toolName, Map<String, Object> arguments, String sessionId,
                       CancellationToken cancellationToken, ToolOutputSink outputSink)
```

- 保留现有 `(toolName, arguments, sessionId)` 与 `(toolName, arguments)` 两个构造器，委托 `CancellationToken.NONE` / `ToolOutputSink.NOOP`。**既有 5 个文件工具与脚本工具一行都不用改。**
- 新增 getter：`getCancellationToken()`、`getOutputSink()`。

#### 4.1.4 `PermissionVeto` → `PermissionVerdict`

```java
package zcd.jellyfish.api.extension;

/** 插件在权限检查扩展点上的裁定，三态。 */
public final class PermissionVerdict {

    public enum Outcome { ABSTAIN, ASK, DENY }

    public static PermissionVerdict abstain();
    public static PermissionVerdict ask(String reason);
    public static PermissionVerdict deny(String reason);
}
```

- 形状与 `PermissionDecision` 一致（带理由的值类型 + 嵌套枚举），**不是裸枚举**：裁定必须能携带理由，审计输出与审批浮层靠它说明「为什么」。
- `PermissionCheckRequest` 的结果类型从 `PermissionVeto` 改为 `PermissionVerdict`。
- **守住的不变式**：枚举里没有 `ALLOW`，因此「插件永远不能放宽核心策略」依旧是**编译期**约束。原设计的实质是「不许放宽」，两态只是实现它的最粗手段；改成三态后实质不变、表达力够用。
- Java 侧内置插件没有任何一处实现权限拦截，但**跨语言侧要一起改**——见 §4.1.5。

#### 4.1.5 跨语言权限协议同步升为三态

这不是可选项：`jellyfish-script` 的 `PermissionCodec` 实现的就是 `ExtensionCodec<PermissionCheckRequest, PermissionVeto>`，而 **Python / Node 两门语言的 SDK 都向脚本作者暴露了 `permission` 贡献点**。只改 Java 侧会让脚本侧与内核的类型对不上。

| 位置 | 改动 |
| --- | --- |
| `jellyfish-script/codec/PermissionCodec.java` | 结果载荷从 `{"denied": bool, "reason": str}` 改为 `{"verdict": "ABSTAIN\|ASK\|DENY", "reason": str}`；类 javadoc 里「只有两态，且这是编译期约束」要改写成「没有 ALLOW，因此永远不能放宽」 |
| `PermissionCodecTest` | 三态往返 |
| Python SDK `_shape_permission` | `None` / `False` → `ABSTAIN`；`True` / 字符串 → `DENY`；**新增** `"ask"` 字符串与 `{"verdict": "ASK"}` 映射 → `ASK`。布尔与字符串简写语义保持不变，老脚本不受影响 |
| Node SDK 的 `register('permission', ...)` 同样处理 | 与 Python 逐条对应 |

脚本侧仍然拿不到审批通道（审批者只能是外壳），因此 `ASK` 对脚本的含义只是「把我拦不住的东西交给人在场时看一眼」——比「只能全盘拒绝」更可用，且没有放宽任何东西。

### 4.2 预览切分：头 30% + 尾 70%

新增 `jellyfish-infra` 内部类 `ToolOutputPreview`，唯一职责是「把一段文本压进字符预算，保留开头与结尾」。

| 规则 | 内容 |
| --- | --- |
| 比例 | 头 30% / 尾 70%（**尾重**）：编译错误、测试失败、退出信息都在末尾。零散的行业实现一律偏尾（Gemini CLI、Roo Code 都是 20/80），50/50 只适合要同时服务 diff/JSON 的通用场景 |
| 预算 | 头尾合计 = 传入预算，省略标记的长度从预算里扣 |
| 断点 | 优先在换行处断开（头取最后一个换行、尾取第一个换行）；断点距边界超过预算一半时退回按字符切，避免一个超长首行把预览压成一小截 |
| 安全 | 切口不得切开代理对（复用现有 `safeCut` 语义） |
| 省略标记 | **必须写明省略了多少行、多少字符**，例如 `\n… 省略 412 行 / 18733 字符 …\n`。头尾不再单调，没有标记时模型很容易把它当成连续内容 |
| 退化 | 预算过小装不下标记时，退化成只保留头（并仍然标注被截断） |

**`ToolOutputLimiter.headByLines` 删除**，改调 `ToolOutputPreview.text(text, budget)`。

#### 4.2.1 结构化结果的截断

`ToolOutputLimiter.shrink` 的约定是「数组与对象保留前缀」。新规则：

| 类型 | 规则 |
| --- | --- |
| 数组 | **前缀 + 哨兵元素 + 后缀**。哨兵是一个字符串元素，形如 `"…省略 123 项…"`。数组常按时间序排列，尾部是最近的结果，与 shell 的动机同源 |
| 对象 | **保持前缀**。对象没有「尾部更重要」的一般理由，加哨兵反而污染键空间 |
| 标量 | 保持现有行为（按字符截断） |

哨兵的长度必须计入预算，否则「收缩后仍在预算内」这条不变式会被破坏。**不允许产出「看起来连续、实际中间断裂」的假结构**——这正是 Codex issue #14206 指出的「就地截断悄悄改变语义」。

#### 4.2.2 信封开销按实际长度计算

现有 `ENVELOPE_OVERHEAD_CHARS = 512` 是拍脑袋常数，而 `_hint` 里带落盘路径（路径受 `sanitize` 的每段 120 字符限制，长会话 id + 长 toolCallId 时信封会超出 `maxToolOutputChars`）。改成按实际长度估算：`toolName.length() + path 长度 + hint 长度 + 标记长度 + JSON 结构开销`。渲染后仍超预算时按差额再收缩一次预览。

实现上收在一个地方：`ToolOutputEnvelope.previewBudget(maxChars, toolName, path, hint)` 给出预览预算，`MAX_FIT_ROUNDS` / `FIT_SLACK` 两个常量给出校正次数与每轮余量，两条调用路径（事后截断、捕获期溢出）共用它们。

**退化情形**：路径与指引本身就长过总上限时（把 `maxToolOutputChars` 配得极小即可复现），预算会缩到 1，信封长度随之超出上限。此时**以保住恢复路径与指引为先**——它们是模型能否自己找回完整内容的唯一依据，而预览已经缩到没有信息量了。

### 4.3 落盘：`ToolOutputStore` 支持增量写入

现有 `store(...)` 一次性写入整串内容，无法满足捕获期落盘。改造：

| 子项 | 内容 |
| --- | --- |
| 新方法 | `SpillWriter open(sessionId, toolCallId, toolName)`：在会话目录下创建临时文件（`.tmp-*.part`），返回可追加的写入器；`commit()` 原子改名并返回最终路径，`abort()` 丢弃并删掉临时文件，两者幂等 |
| 打开失败 | 返回一个「已失效」的写入器（写为无操作、`commit` 返回 `null`），调用方不必为「磁盘不可写」写分支 |
| 复用 | `sanitize` / `sessionDirectory` / `fileName` / 原子改名全部复用，不新增第二套命名 |
| 清理时机 | 从「`store` 写入后」改到「`SpillWriter.commit()` 后」，避免正在写入的临时文件被别的调用清掉 |
| 失败语义 | 不变：落盘失败返回 `null`、只记 WARN、不阻断回合；信封如实记 `_path: null` 与「不可恢复」 |
| 原子性 | 不变：先写临时文件再原子改名（与 PID 文件同口径） |

**并发前提（必须写进类注释）**：同一会话目录不会并发写入——一轮内工具串行 + 一会话一在途回合。否则另一个调用的 `cleanup` 会把正在写的 `.part` 当成「最旧文件」删掉。

### 4.4 捕获期 sink：内存有界的核心机制

新增 `SpillCapturingSink implements ToolOutputSink`（`jellyfish-infra`）。

**状态机**：

```
缓冲期：全部内容攒在内存 StringBuilder（上限 ≈ 预览预算 + 安全边际）
   │  超出阈值？
   ├─ 否 → finish()：返回「summary 行 + 原始文本」（无信封、无文件）
   └─ 是 → 开 SpillWriter，把缓冲回灌进文件，转入溢写期
             溢写期：每个 chunk 同时
               (a) 追加进文件（可丢：超过 spillMaxBytes 后停止写入，但继续计数）
               (b) 维护内存中的「头 N 字符 + 尾环形窗口」
             finish()：返回信封（summary + 头尾预览 + 路径 + 总量）
```

**性质**：内存占用 ≈ 2 × 预览预算（头 + 尾 + 缓冲回灌前的存量），**与命令输出体积无关**。这是「捕获期落盘」真正的收益——不需要「4 MiB 内存天花板」那种赌性设计。

**其余约定**：

| 约定 | 内容 |
| --- | --- |
| `finish()` 幂等 | 内核在 `finally` 兜底调用，保证文件句柄释放与原子改名一定发生 |
| 异常路径 | 工具抛异常时，内核用 `finish()` 的返回值（已捕获内容）拼在错误信息前面，而不是丢弃。超时/取消路径同理：模型拿到「已超时 N 秒 + 已捕获的部分输出 + 落盘路径」 |
| `summary` | 拼接为正文**首行**（如 `cwd: /x · exit: 0 · 耗时: 1.2s`），不新增信封字段。头尾切分保留头部，因此它必然可见；代价（不可机器直读、老化成 stub 后消失）与将来更合理的形态见 §10.1 |
| 线程安全 | `write` 由多条泵线程并发调用，实现内部需同步 |
| 上限 | `spillMaxBytes`（缺省 32 MiB），且运行时钳制为不超过 `react.toolOutput.maxBytes`（当后者 > 0）。超过上限后**只把装得下的那一段写入文件**、之后停止写盘但仍继续计数（写入截到上限为止，而不是整个放弃：否则会留下一个空文件，而信封却告诉模型「文件里有前面一部分」） |
| 落盘不完整的表达 | 信封新增 `_partial` 字段：路径存在但内容只有前一段时为 `true`，`_hint` 与 `stub()` 都据此换成「超出部分未捕获、不可恢复」。没有这一位的话，「完整内容在 path」就是一句假话 |
| 不设「输出体量杀命令」 | 超时已经兜住无界输出（`yes` 会在超时点被终止）；超出落盘上限的部分继续 drain 并丢弃 |

### 4.5 权限三态编排

`PermissionManager.decide` 的编排调整：

```java
PermissionDecision decision = evaluatePolicy(request);   // 不变：显式拒绝 > 需审批 > 允许收窄 > PLAN 白名单
String source = CORE_SOURCE;
if (!decision.isDenied()) {
    for (binding : extensions.bindings(PermissionCheckRequest.class, null)) {
        PermissionVerdict verdict = intercept(binding, request);
        // 取最严：DENY > ASK > ABSTAIN；遇 DENY 短路（结果不可能更宽）
        // 插件返回 ASK 时，记下原因与 owner，但继续看后续插件（可能有更严的）
    }
}
if (decision.isAsk()) {
    decision = resolveApproval(request, decision);        // 不变：fail-closed
    source = APPROVAL_SOURCE;
}
publishAudit(request, decision, source);
```

| 规则 | 内容 |
| --- | --- |
| 顺序 | 插件仍在 PLAN 白名单**之后**，因此所有收窄仍然生效；`ASK` 是「升级」而不是「路径分叉」 |
| 合并 | 取最严（`DENY > ASK > ABSTAIN`）；遇 `DENY` 短路 |
| 审计来源 | 记「作出最严结论的那个 owner」；核心策略与审批的现有来源标识不变 |
| 异常 | 不变：单个插件抛错只记 WARN，按「无异议」处理 |
| 守住的边界 | 插件无法放宽任何一层；`ASK` 最终仍走 `ApprovalChannel`，无审批者 = 拒绝 |

### 4.6 实时输出通道

**tee 点是 sink 自己**：sink 是内核实现，`write(chunk)` 天然可以在写盘的同时转发给外壳，**插件零改动**。

| 层 | 改动 |
| --- | --- |
| core | `ReActListener` 新增 `default void onToolCallOutput(String toolCallId, String toolName, String chunk) {}`；`ReActLooper` 造 sink 时把 tee 接到监听器 |
| TUI | `InflightTurn` 新增 `appendToolOutput(toolCallId, toolName, chunk)`：**有界环形缓冲**（末 20 行 × 每行 200 字符）；`onToolCallCompleted` 时清空（真实结果随后由会话投影渲染）；渲染为「运行中的工具轨迹」块 |
| CLI | `CliReActListener.onToolCallOutput` 写 stderr（既有契约：stdout 严格等于最终回答）。首个 chunk 前补一行工具名前缀 |

**必须写进文档的两条**：

1. **打破「外壳与模型看到同一份文本」的原则**，但只限工具轨迹的**过程展示**。最终落会话、回灌模型的文本仍是同一份，`ToolOutputLimiter` 的截断契约不变。不写清这条，后来的人会把「界面比模型多」当成 bug 去修。
2. **显示通道可丢**（I3）：TUI 缓冲满就丢，不做重试、不做补偿。若 tee 阻塞，capture 会被拖住，子进程会因为管道写满而阻塞——把「少看几行输出」升级成「命令卡死」。

### 4.7 `read_file` 单行超长改为报错

**现状**：单行超过 `max_bytes` 时把这一行切短，并在末尾提示「单行超过 max_bytes 已截断」。

**改为**：区分两种情况——

- **多行超预算**：保持现状（读若干行 + 续读指引），这是正常的分页；
- **单行就超预算**：抛 `JellyfishException`，错误信息给出路——缩小 `limit`、调大 `max_bytes`、或改用 `grep_files` 定位。

理由：切短会产出「看起来完整、实际残缺的一行」，模型无从判断自己拿到的是不是全文（与 I1 同源）。Claude Code 的 Read 工具在单行过大时也是报错并要求缩小 limit 或用 Grep。

影响面：`ReadFileTool.readLines` 的 `lineCut` 分支 + 既有 `ReadFileToolTest` 的相关用例。

### 4.8 插件 `jellyfish-plugin-shell`

#### 4.8.1 归属与装配

- 新建模块 `jellyfish-plugins/jellyfish-plugin-shell`，加入 `jellyfish-plugins/pom.xml` 的 `<modules>`。
- `plugin.properties`：`plugin.id=jellyfish-shell`、`plugin.class=zcd.jellyfish.plugin.shell.ShellPlugin`、`plugin.requires=*`、`jellyfish.tags=shell,exec`。
- 依赖：`jellyfish-api`（**provided**，绝不能 shade 进去，`PluginClasspathGuard` 会拒）+ `org.apache.commons:commons-exec`（compile，**shade 进插件包**）+ 测试用 `jellyfish-infra`。
- shade 配置照抄 python 插件的形状：`artifactSet.includes` 只有 `org.apache.commons:commons-exec`，过滤 `META-INF/*.SF|DSA|RSA` 与 `module-info.class`。

#### 4.8.2 工具名片

工具名 `shell`，`readOnly=false`（PLAN 模式天然被拦）。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `command` | string | 是 | 要执行的命令行原文 |
| `cwd` | string | 否 | 工作目录，相对路径按进程工作目录解析（`ToolPaths` 口径）；缺省进程工作目录 |
| `timeout_seconds` | integer | 否 | 覆盖缺省超时，受 `maxTimeoutSeconds` 钳制 |
| `max_bytes` | integer | 否 | 本次预览预算覆盖（上限为内核 `maxToolOutputChars`） |

描述文本要向模型交代三件事：① `cd` **不跨调用持久**（每次都是新 shell），需要切换目录就用 `cd X && cmd` 或传 `cwd`；② 读文件/搜索应优先用 `read_file` / `grep_files` / `list_dir`；③ 不要用它执行交互式命令（stdin 已重定向到 `/dev/null`，会立刻失败）。

#### 4.8.3 执行

| 项 | 决定 |
| --- | --- |
| 形式 | `/bin/sh -c "<command>"`（POSIX）；Windows 映射 `cmd.exe /c` 但**标注未验证** |
| stdin | 重定向 `/dev/null`。交互式命令（`vi`/`ssh`/`sudo`/`read`）必须快速失败，且绝不能抢 TTY——TUI 是 raw 模式，子进程直接写终端会把界面画烂 |
| cwd | `cwd` 参数 → `ToolPaths.resolve`；缺省进程工作目录。**不做目录围栏**（§1.1） |
| summary | 首行输出 `cwd: <目录> · exit: <码> · 耗时: <秒>`，让模型与审批者都知道这条命令在哪跑的 |
| exit code | **非零如实报告，不抛异常**：`grep` 返回 1 是正常信息，抛异常会把它变成「工具失败」并把 `success` 语义稀释 |
| 环境变量 | 见下 |

#### 4.8.4 环境变量

**继承 + 默认脱敏 + 防挂死覆盖**（不用脚本插件那种严格白名单，那会丢掉 `PATH`，导致几乎所有命令 command not found）。

| 类别 | 内容 |
| --- | --- |
| 默认剔除 | 名字匹配 `*KEY*`、`*TOKEN*`、`*SECRET*`、`*PASSWORD*`、`*CREDENTIAL*` 的环境变量不传给子进程。理由：工具输出会送到远端 LLM，一条 `env` 就是一次外泄 |
| 覆盖注入 | `PAGER=cat`、`GIT_PAGER=cat`、`GIT_TERMINAL_PROMPT=0`（否则 `git push` 会等凭据）、`TERM=dumb`、`NO_COLOR=1`、`DEBIAN_FRONTEND=noninteractive` |
| 可配置 | `environment`（额外注入/覆盖）、`sensitivePatterns`（扩展默认剔除表） |

#### 4.8.5 超时与终止

两条**互相独立**的计时器，谁先到谁触发终止：

| 计时器 | 含义 | 缺省 | 可调 |
| --- | --- | --- | --- |
| 墙上时钟超时 | 最多允许跑多久 | 120 秒 | 插件配置 `timeoutSeconds`；单次调用可用 `timeout_seconds` 覆盖，钳制上限 `maxTimeoutSeconds`（缺省 **1800**） |
| 静默超时 | 连续多久没有任何输出即判定卡住 | **0（关闭）** | 插件配置 `idleTimeoutSeconds` |

- **为什么天花板可以放宽到 1800 秒**：压紧它的原始理由是「Esc 杀不掉在跑的命令，超时是唯一的收敛手段」。批次 1 落地取消令牌后这条理由不再成立——用户随时能掐掉，天花板退化成「防无人看守时傻等」的软护栏。但**放宽必须与实时输出（§4.6）配套**，否则用户要面对一个黑箱干等 30 分钟；两者都在本期，是搭配的。
- **仍然不支持「不超时」**：保留一个上限，避免配置写错变成无限等待。
- **静默超时回答的是另一个问题**：墙钟回答「最多允许跑多久」，静默回答「多久没动静就当死了」。`mvn test` 跑 20 分钟并持续打印进度时，墙钟会误杀而静默不会；但有些步骤确实长时间无输出（`docker build` 缓冲输出、大目录 `grep`），缺省开启会误杀——所以**缺省关闭**，知道自己的命令会长时间静默的人再打开它。
- **静默的实现成本很低**：工具本来就在每条 chunk 上过，记最后一个 chunk 的时间戳即可，等待循环里顺手比较。
- **模型不能设置静默超时**：它是用户的环境策略，不是单次调用的性质。
- 触发时的输出要如实说明是哪一条触发的（「已超时 N 秒」/「连续 N 秒无输出，判定为卡住」），并附上已捕获的部分输出。

| 阶段 | 动作 |
| --- | --- |
| 终止链 | SIGTERM → 宽限 2~3 秒 → SIGKILL |
| 杀树 | `sh -c` 的直接子进程是 shell，杀掉它不会带走 `npm run dev` 拉起的孙进程（JDK 8 没有 `ProcessHandle.descendants()`）。用 `pgrep -P` 递归或进程组尽力杀树；**杀不干净是已知边界，不是 bug** |
| watchdog | 用 commons-exec 的 `ExecuteWatchdog` 做计时器，通过 `setProcessDestroyer` 挂上我们自己的分级杀树逻辑 |

> ⚠️ **与既有先例的关系必须写清**：AGENTS.md 记着「脚本插件不装 `ExecuteWatchdog`」，原因是脚本网关是**长命进程**、而 watchdog 按墙上时钟强杀。shell 命令恰恰相反——每次调用都是短命的一次性进程，超时强杀正是我们要的行为。**场景不同导致结论相反**，文档要把这句话写明，否则后来的人会以为是违反先例。

#### 4.8.6 取消

`ToolCallRequest.getCancellationToken()` 提供取消信号。工具在启动进程后注册 `onCancel`：

- 回调里**只发信号**（SIGTERM/SIGKILL），不等待、不做树递归等待——它可能在 TUI 渲染线程上执行；
- 清理与收尾（关闭流、`finish()`、回收子进程）在工具线程里做；
- 取消后照常 `finish()`，因此模型拿到部分输出而不是空。

#### 4.8.7 输出与解码

| 项 | 决定 |
| --- | --- |
| 合并 | stdout 与 stderr **合并**为一条流（像终端，Claude Code 与 Codex 都是合并），顺序为到达顺序；分节需要引入 channel 概念，收益不大 |
| 解码 | 用有状态的 `InputStreamReader`（UTF-8）而非按字节块 `new String(bytes)`——多字节字符会跨块切开。若走 commons-exec 的 `PumpStreamHandler`，需要自定义 `ExecuteStreamHandler` 以持有 reader，或在 `OutputStream` 适配器里用 `CharsetDecoder` 保留残余字节 |
| 二进制 | 检测到 NUL 字节或高比例非法 UTF-8 时，只报「二进制输出，N 字节已省略」，不把二进制灌进上下文 |
| 管道 | **必须持续 drain**，即使已经放弃保留内容——停止读取会让子进程因管道写满而永久阻塞，表现为「命令卡死」 |
| 落盘 | 交给内核 sink（§4.4） |

#### 4.8.8 前缀白名单（默认关闭）

配置 `allowedCommands`（字符串数组）。**非空时语义为默认拒绝**：命令的第一个 token（或前两个 token，见下）不在列表里就拒绝。

- 匹配粒度：允许写 `git status` 这种两 token 形式；单 token 形式匹配该命令的全部子命令。
- 它服务的场景是 `-cli` / `-server`（那两个模式下 `askTools` 等于禁用，没有人在场批准）。
- 与 §4.8.9 的分类器是两件事：白名单是**默认拒绝**（强），分类器是**减少审批打扰**（弱）。

#### 4.8.9 命令分类器（三态，依赖 §4.1.4 与 §4.5）

插件注册一个 `PermissionCheckRequest` 处理器（**类型级**，因此必须自行判断 `toolName.equals("shell")`，否则会波及其他工具），把命令分成三类：

| 类别 | 裁定 | 默认内容 |
| --- | --- | --- |
| 只读命令 | `ABSTAIN`（不打扰人） | `ls` `cat` `head` `tail` `wc` `pwd` `echo` `which` `type` `date` `uname` `whoami` `id` `df` `du` `ps` `stat` `file`、`git status` / `git log` / `git diff` / `git show` / `git branch` / `git remote -v` / `git rev-parse`、`npm ls` / `pnpm ls` |
| 明确灾难形状 | `DENY` | 极少几条：`rm -rf /`、`mkfs`、`dd of=/dev/*`、fork bomb 形状 |
| 其余 | `ASK`（人看一眼） | 默认 |

**刻意保守的两条**：

- **`find` 不在只读列表里**（`find -delete`、`find -exec rm` 都是写操作），`git fetch` / `git push` 不在（会改远端与本地 ref），`npm test` / `mvn test` 不在（**执行仓库里的任意代码**，写不写文件由仓库决定）。这些都是「看起来无害」的典型误判点。
- **分类器不是安全边界**。它按命令原文的前缀匹配，`FOO=bar cmd`、`$(...)`、别名、`sh -c` 嵌套都能绕过。它的价值是「让只读查询不再打扰人」，从而避免用户因为嫌烦而把 `shell` 从 `askTools` 里整个拿掉——那才是真正的风险。真正的边界是审批本身 + 白名单。

### 4.9 线程与并发语义（本次设计的一处重要发现）

commons-exec 会为 stdout 与 stderr 各起一条泵线程，它们都会调 `sink.write`。因此：

| 对象 | 语义 |
| --- | --- |
| `ToolOutputSink.write` | **必须线程安全**，可能被多条泵线程并发调用 |
| `ReActListener.onToolCallOutput` | **打破「一次回合的全部回调都在同一个 react 线程上」的既有契约**——它会在泵线程上触发。javadoc 必须写明：可能在非 react 线程触发、实现必须线程安全、不得阻塞 |
| `InflightTurn` | 现有方法已经是 `synchronized` + `volatile` 脏标记，**无需改造**即可承受非 react 线程写入；界面状态仍然只在渲染线程变更（既有契约不破） |
| `CliReActListener` | 写 stderr；PrintStream 本身同步，但要避免在 chunk 回调里做额外格式化 |

这条不是实现细节，它影响 api 的契约表述，必须在 §4.1.2 与本节两处都写明。

---

## 5. 配置清单

### 5.1 内核（`jellyfish.json` → `react.toolOutput`）

| 键 | 缺省 | 说明 |
| --- | --- | --- |
| `dir` | `~/jellyfish/tool-outputs` | 不变 |
| `keepFiles` | 200 | 不变 |
| `maxBytes` | 50 MiB | 不变（每会话） |
| `keepRecentMessages` | 20 | 不变 |
| `spillMaxBytes` | **32 MiB** | 新增：单个工具的落盘上限；运行时钳制为不超过 `maxBytes`（后者 > 0 时） |
| `react.maxToolOutputChars` | 20000 | 不变（回灌模型的字符预算） |

头尾比例（30/70）与省略标记格式**做成常量**，不开放配置——它们需要与信封、预览一起演进，多一个旋钮只会多一种配错的组合。

### 5.2 插件（`jellyfish.json` → `plugins.configurations.jellyfish-shell`）

| 键 | 缺省 | 说明 |
| --- | --- | --- |
| `timeoutSeconds` | 120 | 墙上时钟超时：最多允许跑多久 |
| `maxTimeoutSeconds` | **1800** | `timeout_seconds` 的钳制上限 |
| `idleTimeoutSeconds` | **0（关闭）** | 静默超时：连续多久无输出即判定卡住 |
| `environment` | `{}` | 额外注入/覆盖的环境变量 |
| `sensitivePatterns` | 内置 5 条 | 扩展默认剔除表 |
| `allowedCommands` | `[]` | 非空即「默认拒绝」白名单 |
| `commandPolicy.enabled` | `true` | 是否启用命令分类器 |
| `commandPolicy.readOnlyCommands` | 内置列表 | 可追加 |
| `commandPolicy.deniedPatterns` | 内置极少几条 | 可追加 |

配置解析沿用既有口径：非法值回退缺省、只发 `ConfigWarningEvent`、不阻断启动。

---

## 6. 测试计划

### 6.1 单测（进 `mvn test`）

| 目标 | 用例 |
| --- | --- |
| `ToolOutputPreview` | 极短文本（不触发）、恰好等于预算、单行超长、代理对切口、头尾断点在换行处、省略计数正确、预算装不下标记时退化 |
| `ToolOutputLimiter` | 文本路径用头尾、结构化数组含哨兵且仍是合法 JSON、对象保持前缀、信封开销按实长计算后不超预算 |
| `SpillCapturingSink` | 未溢出时不落盘、溢出时落盘且完整、内存占用只与预算相关（喂 10 MiB 断言保留量有界）、`finish` 幂等、`summary` 在首行、超过 `spillMaxBytes` 时信封如实标注、并发 `write` 不丢内容 |
| `ToolOutputStore` | 增量写入 + 原子改名、`close` 后才清理、文件数与字节上限、临时文件命名与清洗 |
| `PermissionManager` | 三态合并取最严、`DENY` 短路、插件 `ASK` 汇入审批、PLAN 白名单仍然优先、插件抛错按无异议 |
| `ReadFileTool` | 单行超长报错（消息含三条出路）、多行超预算仍分页 |
| `ShellPlugin` | 参数校验与钳制、环境脱敏与防挂死注入、白名单默认拒绝语义、分类器三态分类（含 `find` / `git fetch` / `npm test` 必须落在 `ASK`）、两条计时器的判定（含静默计时按最后一条输出的时间戳重置、`0` 时不触发）、`stop` 杀在途进程 |
| `ReActLooper` | 取消令牌传到请求、sink 在 `finally` 被 finish、异常路径拼上已捕获内容 |

进程相关逻辑通过接缝（把"启动进程"抽象成一个接口）用假实现验证，不在单测里 fork 真进程。

### 6.2 端到端（新 profile `shell-it`，进 `-Pshell-it test`）

照 `script-it` 的形状：surefire 只跑 `**/*IT.java`。

| 用例 | 断言 |
| --- | --- |
| 真 `/bin/sh` 跑一个输出小、退 0 的命令 | 无落盘文件，输出含 summary 首行 |
| 退非零的命令 | 如实报告 exit code，`success=true` |
| 输出数万行 | 落盘文件完整，回灌文本是头尾 + 省略标记，路径可被 `read_file` 读到 |
| 超时命令（`sleep`） | 在墙上时钟超时点被终止，返回部分输出 + 超时说明 |
| 静默超时（`idleTimeoutSeconds=2` 跑一个先输出再 `sleep` 的命令） | 在静默点被终止，说明文字指出是静默而非墙钟 |
| 超时后拉起的孙进程 | 尽树被杀（`pgrep -P` 验证），无残留 |
| 取消令牌 | 置位后命令在秒级被终止 |
| `stdin=/dev/null` | `cat` 之类立即返回而不是挂住 |
| 环境脱敏 | 预设一个 `FAKE_TOKEN`，命令里 `env` 看不到它 |
| 大输出压力（`yes`） | JVM 内存不随输出体积增长；进程在超时点终止 |

### 6.3 回归

批次 1 与 2 的主验收是 `mvn -q test` 全绿 + JaCoCo，且**现有 5 个文件工具与脚本插件的端到端（`-Pscript-it`）不受影响**。

---

## 7. 风险与待核实

| # | 项 | 处理 |
| --- | --- | --- |
| R1 | `ExecuteWatchdog` 超时时的销毁语义（默认 `destroy()` 还是 `destroyForcibly()`）与 `setProcessDestroyer` 的实际行为 | 批次 3 开工时先写最小验证程序确认，不照文档假设 |
| R2 | `PumpStreamHandler` 与自定义 `ExecuteStreamHandler` 在「多字节跨块」上的取舍 | 实现时二选一并写进注释；单测覆盖一个跨块的多字节序列 |
| R3 | 杀树在 macOS 上的可靠性（`pgrep -P` 是否总能覆盖） | 尽力而为，文档写清「杀不干净是已知边界」；端到端用 `pgrep` 做尽力断言而非强断言 |
| R4 | 同一会话目录并发写入的假设若被破坏（将来引入并行工具） | 现在写进类注释；将来若破例，`SpillWriter` 需要改名隔离或加锁 |
| R5 | ~~`PermissionVeto` 改名对脚本侧的影响是否需要核实~~ | **已核实，确认要改**：`jellyfish-script/codec/PermissionCodec.java` 与 Python/Node 两套 SDK 都暴露了 `permission` 贡献点，改动清单见 §4.1.5，已并入批次 1 |

---

## 8. 批次与文件清单

| 批次 | 内容 | 主要文件 |
| --- | --- | --- |
| **1** | api 冻结 + 截断/落盘/捕获期 sink + 跨语言权限协议 + `read_file` 报错 | 新增 `api/extension/CancellationToken.java`、`api/extension/ToolOutputSink.java`、`api/extension/PermissionVerdict.java`、`infra/tooloutput/ToolOutputPreview.java`、`infra/tooloutput/SpillCapturingSink.java`；改 `api/extension/ToolCallRequest.java`、`api/extension/PermissionCheckRequest.java`、删 `api/extension/PermissionVeto.java`、`script/codec/PermissionCodec.java`、`plugin-python/.../script/jellyfish_sdk.py`、`plugin-node/.../script/jellyfish_sdk.js`、`infra/tooloutput/ToolOutputLimiter.java`、`infra/tooloutput/ToolOutputStore.java`、`infra/config/ToolOutputSettings.java`、`infra/config/ReactSettings.java`、`core/ReActLooper.java`、`plugins/jellyfish-plugin-tools/.../ReadFileTool.java`，以及对应的全部测试（含 `PermissionCodecTest` / `PermissionVetoTest` → `PermissionVerdictTest`） |
| **2** | 权限三态编排 + 实时输出通道 | 改 `infra/permission/PermissionManager.java`、`core/ReActListener.java`、`core/ReActLooper.java`、`tui/InflightTurn.java`、`tui/TuiReActListener.java`、`tui/TranscriptProjector.java`（渲染运行中轨迹）、`cli/console/CliReActListener.java` |
| **3** | shell 插件全量 | 新增模块 `jellyfish-plugins/jellyfish-plugin-shell`（`plugin.properties` / `ShellPlugin` / `ShellTool` / `ShellArguments` / `ShellEnvironment` / `CommandPolicy` / `ShellProcessRunner` / `PluginConfig` + pom 的 shade 配置）；改 `jellyfish-plugins/pom.xml` |
| **4** | 文档与端到端 | 新增 `docs/shell-tool-design.md`（本文）已存在；新增 `jellyfish-plugin-shell/src/test/.../*IT.java` 与 `shell-it` profile；改 `AGENTS.md` |

`AGENTS.md` 在批次 4 需要补的内容：新插件条目与模块表、三条不变式（I1/I2/I3）、取消令牌语义与「回调必须快」、捕获期 sink 的角色与「两个触发点一份实现」、权限三态与「插件不能放宽」的新形式（含脚本侧 `permission` 由两态升为三态）、`read_file` 单行超长报错、shell 的「无沙箱」声明与 `askTools: ["shell"]` 推荐、两条计时器（墙钟 + 静默，后者缺省关闭）的存在与分工、watchdog 与脚本插件的场景差异。

---

## 9. 设计依据

### 9.1 主流实现对照（用于校验方向）

| 实现 | 截断方向 | 限值 | 落盘 |
| --- | --- | --- | --- |
| Codex CLI | 头+尾 **50/50**（UTF-8 安全，标记 `…N tokens truncated…`） | 约 10 KiB / 256 行 | 否（issue #14206 正在提议改为落盘） |
| Claude Code（Bash 成功） | 只留头（预览前 2000 字符） | 内联 30k 字符（上限 150k） | **是，运行期即流式写入工作文件**；超 5 GB 杀命令 |
| Claude Code（Bash 失败） | 头+尾 | 约 10k 字符 | 否 |
| Gemini CLI | 头 20% + 尾 **80%**，标记 `[N characters omitted]` | 40k 字符 | 是（临时文件 + 路径） |
| Roo Code / Cline | 行级头 20% + 尾 **80%** | 100k 字符 / 5000 行 | 新版有 |
| OpenHands | 头+尾（`maybe_truncate`） | 约 30 KB | 可选 |
| SWE-agent | 只留头 + `<response clipped>` | 约 10k 字符 | 否 |
| OpenAI Agents SDK | 老轮次滑动窗口裁剪 | `max_output_chars` | 否 |
| LangChain Deep Agents | 大结果先卸载到文件系统 | — | 是 |

**结论**：头+尾 + 显式省略标记是事实标准；比例偏尾（我们取 30/70）；落盘是趋势（我们采用）；Claude Code 的「捕获期落盘」是本方案 §4.4 的直接依据；我们已有的 `ToolResultAger` + stub 保留路径比 Agents SDK 的纯裁剪更完整，不动。

### 9.2 被否决的备选

| 备选 | 否决理由 |
| --- | --- |
| 插件自己落盘 | 只能拿到 `jellyfish-api`，会形成第二套目录/命名/清理/提示格式 |
| shell 用 4 MiB 内存天花板 + 超出未捕获 | 是赌性设计；捕获期落盘（§4.4）以更低代价解决同样的问题 |
| 内核统一给工具调用加超时（`Future.get` + `cancel(true)`） | `interrupt` 不能中断 `Process` 的流读取，只会把泄漏藏进线程池 |
| 目录围栏 | 见 §1.1 |
| 命令黑名单 / 解析式安全 | 见 §1.1 |
| 端到端测试直接进 `mvn test` | 违反既有约定（单测不访问外部资源）；用 `shell-it` profile |
| 头尾比例做成配置 | 它与信封、预览实现一起演进，多一个旋钮只多一种配错的组合 |

---

## 10. 后续优化项（本期不做）

### 10.1 工具结果元数据升为一等公民

**本期做法**：`summary` 被拼成正文首行（§4.4）。

**为什么现在这样**：summary 必须在「未溢出」（纯文本）与「溢出」（JSON 信封）两条路径上都可见，而信封只存在于后者——正文首行是唯一能保证「退出码永远在同一个地方」的位置。同时信封字段的定位是**内核自己要读**的元数据（`_truncated` 给老化判定、`_path` 与 `_total_chars` 给 `stub()`），而 summary 是工具的语义、内核从不读它，塞进信封等于让内核背一个自己不用却要跟着 schema 演进的字段。

**它的代价**：退出码等元数据只能被模型「读文本」消费，不能机器直读；上下文老化成 `stub()` 后消失（路径仍在，回查一跳）。注意：**字段方案也救不了老化那一条**，除非同时改 `stub()`。

**更合理的形态**（下一期）：

1. **`ToolCallResult` 承载结构化元数据**，而不是让工具拼一行 prose：`ToolCallResult(toolName, output, metadata)`，metadata 是一份有约定小 schema 的映射（`exitCode` / `durationMs` / `cwd` / `binary` …）。
2. **内核统一渲染**：非溢出路径把元数据渲染成固定的首行（**位置不变**），溢出路径同时写进信封字段。这样既机器可读、又保住「两条路径同一位置」这条性质。
3. **`ToolResultAger.stub()` 按声明保留关键字段**（退出码、路径），于是「退出码」这类信息能穿过上下文老化。
4. **顺带解锁两件事**：TUI 可以按元数据渲染语义（非零退出码 → 警告标记），指标与审计可以按退出码统计。

**为什么不在本期做**：它要改 `ToolCallResult`（api 面）与 shell 之外的调用点，而且需要先想清楚「元数据 schema 归内核还是归工具」。等本轮跑起来、真实看到哪些元数据值得保留，再定 schema 更稳。

**与本期决策的关系**：本期的「正文首行」是它的**子集**——「位置一致」这条性质被继承下来，所以这次的选择是可加性的，不是将来需要推翻的临时方案。
