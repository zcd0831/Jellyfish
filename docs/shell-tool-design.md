# shell 命令执行工具 · 技术设计文档

- 状态：已评审；**批次 1、2、3、4 全部落地**（见 §8），并已完成收尾核算（见 §10：做掉的、明确不做的、留待下一期的）
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
     * <p><b>必须持续接受写入</b>：实现不得把自己写成「写一次就以为了事」——超时、取消、异常
     * 三条路径都靠已捕获内容拼出回灌文本。
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

**实时输出不是这个接口的一部分**：`ToolOutputSink` 上没有「实时」相关的方法，因为 tee 发生在内核自己实现的那个 sink 里（插件拿到的只是一个接口，它无从阻止也无需知道）。这条设计的后果是：**把 sink 换成别的实现，实时输出就会默默消失**，因此 §4.9 的契约要写在 `ReActListener` 上而不是 `ToolOutputSink` 上。

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
| TUI | `InflightTurn` 新增 `beginTool(toolName)` / `appendToolOutput(chunk)` / `clearToolOutput()`：**有界行缓冲**（末 20 行 × 每行 200 字符，另存「尚未换行的当前行」）；`onToolCallCompleted` 时清空（真实结果随后由会话投影渲染）；`TranscriptProjector` 渲染为「运行中的工具轨迹」块（`⎿ 工具名` + `│ 输出行`） |
| CLI | `CliReActListener.onToolCallOutput` 写 stderr（既有契约：stdout 严格等于最终回答）。首个 chunk 前先写一行 `│ 工具名`，每行行首补同一个缩进 |
| Server | `SseReActListener.onToolCallOutput` 投一条 `tool_output` 事件（`turnId` / `toolCallId` / `toolName` / `chunk`）。**唯一自带上限的通道**：待发条数超 64 即丢弃并计数——它的生产者是子进程而不是模型，消费端可能是慢连接，无界队列会跟着涨（见 §10.2） |

**实现时定下的细节（都不需要配置）**：

- **行缓冲分「已完整的行」与「尚未换行的当前行」两块**：命令的最后一行往往不带换行，而一个以换行结尾的片段也不该凭空多出一个空行。分开之后两种情形都不需要特判；行数上限在**取快照时**统一施加，否则「当前行」会成为上限之外的额外一行。
- **单行超长保留行首**：行的开头通常是它的身份（JSON 的左花括号、日志的时间戳与级别），而尾部会在工具返回后的权威结果里完整出现。
- **实时块不补表头**：行到那里时通常已在助手块内（上一轮 `assistant` 消息刚落下），无条件补会出现两个相连表头。沿用 `appendToolTrace` 的同一判断。
- **实时块不标「已截断」**：它终将被会话投影出的正式轨迹与结果取代，而「已截断」会被读成「工具结果被截断」。
- **控制字符在显示边界滤掉**（`ControlChars.strip`，与 `MarkdownRenderer` / `ApprovalPrompt` 同一位置）：命令输出里的一段 `ESC[2J` 能清屏。
- **渲染优先级**：正文/思考有内容 → 显示正文（模型已又开始说话）；否则有工具轨迹 → 显示工具轨迹；否则「处理中…」。

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

> **原设计里的 `max_bytes` 已去掉，且经核算后明确不做**：预览预算由内核在造 sink 时固定
> （`react.toolOutput` 那一段），插件拿到的只是一个 `ToolOutputSink` 接口，没有任何地方能覆盖它；
> 而调大它是上下文脚枪、调小它不如直接在命令里写 `head -50`。完整论证见 §10.3。

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

### 4.8.10 实现时定下的细节（落地后补记）

**1. stdin 用「关闭管道」而不是 ` < /dev/null` 重定向**

两者对子进程是等价的（读 stdin 立刻得到 EOF），而关闭管道是库自己的机制，它不需要改写用户写的命令原文。

**2. 不使用 `ExecuteWatchdog`（与 §4.8.5 表格的差异，理由如下）**

三条终止来源（墙钟超时、静默超时、取消）全部在同一个等待循环里判定，循环切片 100 ms。
把墙钟交给 `ExecuteWatchdog`、另外两条留在循环里，同一次调用就有两个发起方，事后没人能说清是谁动的手。
而且 §4.8.5 自己写的实现方式就是「等待循环里顺手比较（最后一条输出的时间戳）」——那份描述已经蕴含了循环。
<b>与 AGENTS.md 里「脚本插件不装 watchdog」并不矛盾</b>：那里的理由是「网关长命、硬超时会杀掉正常运行时」，这里两者的立场其实一致——不给长命进程装墙钟，一次性命令则用自己的循环收口。

**3. 跨块解码要自己留残余字节**

`CharsetDecoder.decode(in, out, false)` 遇到不完整的多字节序列时会返回 UNDERFLOW 并把那几个字节
<b>留在输入缓冲里不消费</b>（指望调用方下次一起再喂进来）。每块各写各的 `ByteBuffer` 就等于丢掉
那个半截字符——现象是「中文日志偶尔一个乱码」。因此捕获流自持一份 `pending` 残余字节，
并在收尾时按「输入结束」把它解成替换字符。

**4. 二进制两条判据**

出现 NUL 字节即判定；或替换字符（U+FFFD）达 4 个且占比超过 5%。
判定之后仍然继续读与计数（停止读取会让子进程因管道写满而卡住），只是不再进上下文。

**5. 「取消」必须优先于「进程自己退出了」的判定**

取消回调会直接给子进程发信号，因此等待循环下一拍会看到「进程已退出」。
先判退出会把取消误报成正常完成（还带着一个 143 的退出码）。
现在的顺序是：先看令牌，再看 `waitFor` 的结果，且退出后再确认一次令牌。
这条是单测发现的（`run_should_killProcess_whenCancelled` 最初拿到 `COMPLETED`）。

**6. 进程标识只能尽力取**

`Process.pid()` 是 Java 9+ 的方法，Java 8 上只能读 `UNIXProcess` 的私有字段，再不行就解析
`toString()` 里的 `pid=NNN`。三条都失败时退化为只杀直接子进程并记 DEBUG。
`pgrep` 不存在、没权限、或进程在这中间又生了孩子，都只能放弃——<b>杀不干净是已知边界</b>。

**7. 工具名在权限处理器里要再判一次**

`PermissionCheckRequest` 是类型级扩展点，不判工具名就会给其它工具下结论。
拿不到 `command` 原文时一律 `ASK`（空声明等于放行）。

**8. 元数据行的两个作用点**

工具把 `ShellResult.summary(...)` 交给 `sink.summary(...)`，并在 `sink.finish()` 返回空时
（非内核调用点拿到的是 `NOOP`）把这行直接作为输出返回——否则模型看到一片空白，
无法区分「命令没输出」与「命令没跑」。

### 4.9 线程与并发语义（本次设计的一处重要发现）

commons-exec 会为 stdout 与 stderr 各起一条泵线程，它们都会调 `sink.write`。因此：

| 对象 | 语义 |
| --- | --- |
| `ToolOutputSink.write` | **必须线程安全**，可能被多条泵线程并发调用。tee 在 sink 的锁**之外**调用：显示慢不应拖住捕获（I3） |
| `ReActListener.onToolCallOutput` | **打破「一次回合的全部回调都在同一个 react 线程上」的既有契约**——它会在泵线程上触发。javadoc 写明：可能在非 react 线程触发、可能被并发调用、实现必须快且不得阻塞；并写明它**不是权威文本** |
| `InflightTurn` | 新增方法仍走同一把实例锁（`synchronized`）与同一个 volatile 脏标记，**界面状态仍然只在渲染线程变更**（既有契约不破）；已有方法一行未改 |
| `CliReActListener` | 写 stderr；`onToolCallOutput` 与它读写的几个行状态加了同一把锁，不做额外格式化 |
| tee 的异常边界 | 监听器抛错在 `SpillCapturingSink` 里被捕获并降级为 DEBUG 日志——显示通道的故障不得把一次工具调用升级成失败 |

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
| `ShellTool` | 元数据行在正文首行、非零退出码回灌为结果、`cwd` 不存在时提前报错、超时缺省与钳制、静默超时只来自配置、环境叠加、`NOOP` sink 的兜底 |
| `ShellArguments` | 命令必填与去空白、`cwd` 可选、`timeout_seconds` 支持数字与数字字符串、小于 1 报错而不是被当成「不超时」 |
| `PluginConfig` | 缺省值、非法值与负数回退缺省不阻断启动、超硬上限钳制、数字字符串被接受、环境/两个列表的解析与非容器类型的容错 |
| `CommandPolicy` | 三态分类（含 `find` / `git fetch` / `npm test` / `sed -i` 必须落在 `ASK`）、白名单默认拒绝且不受分类器开关影响、单/双 token 匹配粒度、两张表可追加 |
| `ShellPermissionContribution` | 别的工具不表态、拿不到命令原文时 `ASK`、只读 `ABSTAIN`、其余 `ASK`、灾难形状 `DENY` |
| `ShellOutputCapture` | 逐字节写入仍能正确解码、收尾冲残余、NUL 与替换字符占比两条二进制判据、少量的坏字节不误判、收尾后的写入被忽略 |
| `ShellProcessRunner` | 非零退出码不抛异常、墙钟与静默两条计时器（含「持续有输出不误杀」与「关闭时不触发」）、取消后进程必死且结果是「已取消」、取消优先于「进程自己退出了」、不响应 TERM 的进程被强杀、命令起不来才抛异常、二进制只计数、`killAll` |
| `ShellResult` | 四条终止路径各自的元数据行、二进制字节数、无 `cwd` 时不出现空段、小数点是点号 |
| `ShellPlugin` 加载链 | 真实描述符启动成功、工具可路由且声明为可写、权限处理器被登记为类型级贡献、卸载后两类注册都被回收 |
| `ReActLooper` | 取消令牌传到请求、sink 在 `finally` 被 finish、异常路径拼上已捕获内容、**工具写入的片段按原样转发给监听器（带 `toolCallId` / `toolName`）、监听器抛错不影响工具结果** |
| `InflightTurn` | 工具轨迹：名字先于输出可见、按换行拆行、行数上限保留最新、单行超长保留行首、清空后不再显示、`begin` 连带重置 |
| `TuiReActListener` | 实时输出写进暂存区并置脏标记、`onToolCallCompleted` / 回合终结时清掉 |
| `TranscriptProjector` | 运行中工具块的前缀与层级、已在助手块内时不重复表头、正文优先于实时输出、控制字符被滤掉、空行不留竖线、清空后回到「处理中…」 |
| `CliReActListener` | 实时输出走 stderr 而 stdout 一字不变、每行只补一次缩进、半行输出在工具结束行之前先收尾、切换工具时另起一块 |
| `SseReActListener` | `tool_output` 事件的四个字段、待发超 64 条时丢弃并计数（取走之后又允许继续推）、空片段不入队 |
| `ChatHandler` | 事件流里真的出现 `event: tool_output` 与其 `chunk` 载荷（守着「监听器发了但外壳没接」这种断链） |
| `ToolOutputEnvelope` | `stub()` 保留预览首行、结构化预览不取首行、首行超 400 字符时截断 |
| `ToolResultAger` | 老化后仍能从 stub 里读到结论行（`exit: 1`） |

进程相关逻辑通过接缝（把"启动进程"抽象成一个接口）用假实现验证，不在单测里 fork 真进程。

### 6.2 端到端（新 profile `shell-it`，进 `-Pshell-it test`）

照 `script-it` 的形状：surefire 只跑 `**/*IT.java`。

| 用例 | 断言 | 已落地 |
| --- | --- | --- |
| `echo 你好` | 输出与 `exit: 0` 都能取回 | ✅ |
| `echo 正常; echo 出错 1>&2` | stderr 与 stdout 合并进同一条流 | ✅ |
| `exit 3` | 如实报告退出码，不抛异常 | ✅ |
| `pwd`（带 `cwd`） | 命令真的在指定目录里跑（用规范路径比较） | ✅ |
| `seq 1 2000` | 最后一行必须还在：证明管道被持续排空 | ✅ |
| `sleep 30` + `timeout_seconds=1` | 在墙钟超时点被终止，且 `sleep` 进程不残留 | ✅ |
| `sleep 30` + 取消令牌 | 终止并标记为「已取消」，`sleep` 不残留 | ✅ |
| `sleep 30` + `idleTimeoutSeconds=1` | 在静默点被终止 | ✅ |
| 循环 `echo` + `sleep 0.4` + 静默 1 秒 | 持续有输出时**不**被静默计时器误杀 | ✅ |
| `cat`（读 stdin） | 立刻返回而不是挂住 | ✅ |
| `head -c 512 /dev/urandom` | 二进制不进正文，只报字节数 | ✅ |
| 输出数万行的回灌与落盘 | 属内核截断中间件，已由批次 1 的单测覆盖，不在本批端到端里重复 | — |
| 大输出压力（`yes`） | 内存不随输出体积增长：同一性质由批次 1 的 `SpillCapturingSinkTest` 喂 10 MiB 断言保留量有界覆盖 | — |

### 6.3 回归

主验收是 `mvn -q test` 全绿 + JaCoCo，且**现有 5 个文件工具与脚本插件的端到端（`-Pscript-it`）不受影响**；
Server 侧另有 `-Pserver-it`（真 Undertow + 真内核走回环）。

**收尾核算时新增的四个组**（`-server` 实时输出 + stub 保留结论行）：`SseReActListenerTest` 13 例、
`ChatHandlerTest` 7 例、`ToolOutputEnvelopeTest` 12 例、`ToolResultAgerTest` 5 例，全绿。

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
| **1** | api 冻结 + 截断/落盘/捕获期 sink + 跨语言权限协议 + `read_file` 报错 | 新增 `api/extension/CancellationToken.java`、`api/extension/ToolOutputSink.java`、`api/extension/PermissionVerdict.java`、`infra/tooloutput/ToolOutputPreview.java`、`infra/tooloutput/SpillCapturingSink.java`；改 `api/extension/ToolCallRequest.java`、`api/extension/PermissionCheckRequest.java`、删 `api/extension/PermissionVeto.java`、`script/codec/PermissionCodec.java`、`plugin-python/.../script/jellyfish_sdk.py`、`plugin-node/.../script/jellyfish_sdk.js`、`infra/tooloutput/ToolOutputLimiter.java`、`infra/tooloutput/ToolOutputStore.java`、`infra/config/ToolOutputSettings.java`、`core/ReActLooper.java`、`plugins/jellyfish-plugin-tools/.../ReadFileTool.java`，以及对应的全部测试（含 `PermissionCodecTest` / `PermissionVetoTest` → `PermissionVerdictTest`）。**已落地**：权限三态编排（原属批次 2）因类型变更不得不同批完成 |
| **2** | 实时输出通道 | 改 `core/ReActListener.java`、`core/ReActLooper.java`、`tui/InflightTurn.java`、`tui/TuiReActListener.java`、`tui/TranscriptProjector.java`（渲染运行中轨迹）、`cli/console/CliReActListener.java`。**已落地** |
| **3** | shell 插件全量 | 新增模块 `jellyfish-plugins/jellyfish-plugin-shell`（`plugin.properties` / `ShellPlugin` / `ShellTool` / `ShellArguments` / `ShellInvocation` / `ShellResult` / `ShellEnvironment` / `CommandPolicy` / `ShellPermissionContribution` / `ShellProcessRunner` / `ShellProcess` / `ShellProcessLauncher` / `CommonsExecShellProcessLauncher` / `ShellOutputCapture` / `ProcessTrees` / `PluginConfig` + pom 的 shade 配置）；改 `jellyfish-plugins/pom.xml`。**已落地**（99 个单测 + 11 个端到端用例） |
| **4** | 文档与端到端 | 新增 `docs/shell-tool-design.md`（本文）已存在；新增 `jellyfish-plugin-shell/src/test/.../*IT.java` 与 `shell-it` profile；改 `AGENTS.md` |

`AGENTS.md` 在批次 4 需要补的内容：新插件条目与模块表、三条不变式（I1/I2/I3）、取消令牌语义与「回调必须快」、捕获期 sink 的角色与「两个触发点一份实现」、权限三态与「插件不能放宽」的新形式（含脚本侧 `permission` 由两态升为三态）、`read_file` 单行超长报错、shell 的「无沙箱」声明，以及与 `askTools` 的真实关系（见 §10.5）、两条计时器（墙钟 + 静默，后者缺省关闭）的存在与分工、watchdog 与脚本插件的场景差异。

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

## 10. 收尾核算：做掉的、明确不做的、留待下一期的

### 10.1 工具结果元数据：只做「穿过老化」，不做结构化 metadata

**本期做法**：`summary` 被拼成正文首行（§4.4）。

**为什么现在这样**：summary 必须在「未溢出」（纯文本）与「溢出」（JSON 信封）两条路径上都可见，而信封只存在于后者——正文首行是唯一能保证「退出码永远在同一个地方」的位置。同时信封字段的定位是**内核自己要读**的元数据（`_truncated` 给老化判定、`_path` 与 `_total_chars` 给 `stub()`），而 summary 是工具的语义、内核从不读它，塞进信封等于让内核背一个自己不用却要跟着 schema 演进的字段。

**收尾时发现的事实（比原描述更严重）**：原以为「老化后路径还在，回查一跳」就够了，实际上**落盘文件里没有元数据行**——
`SpillCapturingSink.write(chunk)` 只把正文写盘，元数据行是 `renderEnvelope()` 事后拼上去的
（`prefix = summary + "\n"`）。于是溢出 + 老化之后，退出码/终止原因/cwd 在上下文里没有了、在文件里也没有。
测试自己就把这条钉住了：`assertEquals(expected.toString(), read(envelope.getPath()))` 的期望值是**纯正文**。

**已做的修法（不动 api、不动 schema）**：`ToolOutputEnvelope.stub()` 保留**预览首行**。
预览首行按设计就是元数据行（`prefix` 恒定前置），而 `preview` 本来就在信封里，
所以既不需要新字段（§4.4 那条「内核背一个自己不用的字段」的反对意见不成立——`stub()` 就是它的用处），
也让「结论在正文首行」这条不变式从装饰性变成承重的。边界：

- **只在文本预览上取首行**：结构化预览的「首行」是右花括号/方括号，没有结论的含义；
- **首行超过 400 字符时截断**：首行本身可能是巨长的一行（单行 JSON、压缩日志），原样搬进 stub 等于把老化省下的上下文又还回去；
- 取不到就退回原行为（降级安全）。

**结构化 metadata 仍然不做**（`ToolCallResult(toolName, output, metadata)` 那一版）：

1. **成本被低估**：它要动 `ToolCallResult`（api 面）→ 所有构造点，脚本桥接还要过两个语言的 `ExtensionCodecs`；
   而所需的关键价值（穿过老化）已经用上面那一行拿到了。
2. **收益今天为零**：它想解锁的「TUI 按退出码渲染警告标记」「指标按退出码统计」，
   **一个消费方都还不存在**。等第一个真实消费方出现时再定 schema 更稳。
3. 届时若真要做，第 2 步「内核统一渲染成固定首行」的位置契约已经由本期钉住了，可直接沿用。

### 10.2 Server 的实时输出（已落地）

**已实现**：`tool_output` 事件（`turnId` / `toolCallId` / `toolName` / `chunk`）+ `SseReActListener` 的待发条数上限（64，超出即丢弃并计数）。

**落地时定下的两点**：

1. **它是唯一自带上限的通道**：`SseReActListener` 的无界队列是刻意的（正文增量受模型输出上限约束，丢了即错），
   而实时工具输出的生产者是子进程、消费者可能是慢连接，因此这里必须封顶。**按条数封顶而不是字节数**：
   调度单位就是条，一条事件即一帧，泵每次交付的片段大小固定，条数上限同时就是内存上限。
2. **丢弃不打断任何东西**：丢弃只计数、只记日志，权威文本随后由 `tool_done` 给出——
   这正是 I3「显示通道可丢、捕获通道不可丢」在第三条外壳上的同口径落地。

**遗留**：丢弃计数目前只在监听器上可观测（测试与台账），没有推给客户端。客户端要自己意识到
「实时流有缺口、以 `tool_done` 为准」——README 里已写明这条约定。

### 10.3 每次调用的预览预算覆盖（`max_bytes`）：**明确不做**

**原设计的形状**：给 api 加带预算的 sink 工厂，让 `shell` 能按调用覆盖预览长度。

**结论：不做**，理由是参数的两个方向都没有正收益：

| 方向 | 后果 |
| --- | --- |
| **调大** | 预览预算是**上下文保护**机制（`react.toolOutput.maxToolOutputChars`），让调用方抬它就是造一个撑爆上下文的脚枪——还得靠内核钳制兜底，那这个参数的存在意义就只剩「多一层」 |
| **调小** | 模型**事先并不知道**该截到多少才合适；而命令自己就能整形输出（`head -50`、`grep -m 20`、`--quiet`），这才是惯用做法，还顺带省掉计算 |

也就是说它**没有正收益方向**，只是给 api 加一个面。保留「已记录的缺口」比补上它更好——
真要收紧某条命令的输出，本来就该在命令里写。

### 10.4 只读分类对重定向与复合命令不设防：**明确不做**

**现状**：`echo x > /etc/y` 会被判为只读（首个 token 是 `echo`）而不再打扰用户；
`ls && rm -rf x` 同理（首个 token 是 `ls`）。

**结论：不做**，真正的防线已经部署，而改分类器的代价与风险都更高：

- **审批框里显示的是完整命令原文**（`ls && rm -rf x` 一字不差地给人看）。人看一眼就拒了——
  这比让分类器猜意图有效得多，也正是「审批是安全边界」这句话的落地形式。
- **改分类器等于写半个解析器**：`>` 要区分 `2>&1`、引号内的 `>`、heredoc；`&&` 一收紧，
  最常见的 `git status && git diff` 就变成打扰。写不干净就是「有防线的错觉」，正是 §1.1 拒绝的那类设计。
- **唯一的例外场景是 `-cli` / `-server`**：那里没有审批者，分类器的「只读」判定事实上是唯一放行口。
  但那两个模式的正确答案已经存在——`allowedCommands` 非空即默认拒绝。若确实要收紧，
  应当收紧<b>白名单</b>（安全机制）而不是在分类器（便利机制）上打补丁。

### 10.5 `shell` 与 `askTools` 的真实关系（写文档时才发现的语义）

**起初的写法**（已纠正）：建议把 `shell` 放进 `askTools`，理由是「分类器会代它把只读查询放行」。

**实际不是这样**：`PermissionManager.decide` 先跑核心策略，`askTools` 命中即得 `ASK`；之后插件的裁定只能
**升级**——`ABSTAIN` 是「无异议」，不参与降级。因此 `askTools: ["shell"]` 的结果是
**每条命令都要批准**（连 `git status` 也要），分类器那一刻形同虚设。

| 配置 | 只读命令 | 写类命令 |
| --- | --- | --- |
| `shell` **不在** `askTools`（缺省） | 无异议 → 静默执行 | 分类器 `ASK` → 弹一次批准 |
| `shell` **在** `askTools` | 核心 `ASK` → 也要批准 | 也要批准 |

**因此文档口径应当是**：缺省即是推荐姿态；`askTools: ["shell"]` 是「最强姿态、代价是全都要点」；
`-cli` / `-server` 没有人在场，`askTools` 等于禁用，那两个模式靠 `allowedCommands`。
