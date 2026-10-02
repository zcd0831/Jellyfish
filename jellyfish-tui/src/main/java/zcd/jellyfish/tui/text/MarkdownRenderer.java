package zcd.jellyfish.tui.text;

import dev.tamboui.style.Style;
import zcd.jellyfish.infra.support.ControlChars;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Markdown 渲染器：把模型输出的一段 markdown 渲染成视觉行。
 * <p>
 * <b>为什么只借 commonmark 的解析器、渲染自己写</b>：本项目已经有一套自己的文本模型
 * （{@link VisualLine} / {@link StyledSegment}）与换行口径（{@link LineWrapper}），滚动按视觉行计数。
 * 用现成的 HTML 渲染器等于把「屏幕上有几行」这件事交给别人，滚动位置立刻与内容对不上；
 * 因此这里只取 AST，往下的每一行都由我们自己产出。
 * <p>
 * <b>输入是「正文列数」而不是「屏幕列数」</b>：本类不认识消息缩进（{@code BODY_INDENT}），
 * 它按给定的列数换行、不额外加缩进；调用方（{@link zcd.jellyfish.tui.TranscriptProjector}）
 * 对每条结果行统一补上缩进。两者相减就是屏幕列数，交付给渲染引擎的宽度因此恒不超过屏幕宽度。
 * <p>
 * <b>两条硬约束</b>：
 * <ul>
 *     <li><b>永不抛异常</b>：输入是模型正在生成的半成品（未闭合的 {@code ```}、被截断的链接），
 *     渲染失败绝不能把整个界面带下去；解析失败或深度失控时退回纯文本铺出原文。</li>
 *     <li><b>过滤控制字符</b>：markdown 文本里可能有 {@code ESC} / {@code \r}，
 *     放进去就能改写屏幕（见 {@link ControlChars}）。这一步在解析<b>之前</b>做，
 *     否则 {@code ESC} 会先被当作正文进 AST，再出现在输出里。</li>
 * </ul>
 *
 * @author zcd
 */
public final class MarkdownRenderer {

    /**
     * 解析器。
     * <p>
     * 全局复用一份：{@code Parser} 的解析上下文是每次 {@code parse()} 新建的，因此它是线程安全的；
     * 而构造成本（一堆块级解析器工厂）没必要每帧付一次。
     * <p>
     * 挂两个 GFM 扩展：<b>表格</b>是为了拿到它的行列结构（不识别的话一堆管道符会当普通段落铺开，
     * 连「哪里是一格」都看不出来）；<b>删除线</b>是因为它的目标样式
     * （{@code Style.crossedOut()}）终端直接支持。
     */
    private static final Parser PARSER = Parser.builder()
            .extensions(Arrays.asList(TablesExtension.create(), StrikethroughExtension.create()))
            .build();

    /** 无序列表标记，按嵌套层数轮换，让层级在视觉上可分辨（终端没有缩进线可依赖）。 */
    private static final String[] BULLETS = {"\u2022 ", "\u25e6 ", "\u25aa "};

    /** 引用块前缀（竖线 + 空格，2 列）。 */
    private static final String QUOTE_PREFIX = "\u2502 ";

    /** 标题前缀（左半实心块 + 空格，2 列）。 */
    private static final String HEADING_PREFIX = "\u258c ";

    /** 代码块围栏。 */
    private static final String FENCE = "```";

    /** 分隔线字符。 */
    private static final String RULE_CHAR = "\u2500";

    /** 表格竖线（与引用块前缀同一个字符，但这里是网格的一部分）。 */
    private static final String TABLE_BAR = "\u2502";

    /** 表格单元格左右内边距（列）。 */
    private static final int CELL_PADDING = 1;

    /**
     * 表格每列除内容之外的固定开销：左右内边距 2 列 + 每列右侧的竖线 1 列。
     * <p>
     * 最后再加一列（{@link #GRID_OVERHEAD_EDGE}）给整张表最左侧的那条竖线。
     */
    private static final int GRID_OVERHEAD_PER_COLUMN = CELL_PADDING * 2 + 1;

    /** 表格最左侧竖线占用的列数。 */
    private static final int GRID_OVERHEAD_EDGE = 1;

    /**
     * 表格列宽下限。
     * <p>
     * 分不到这个宽度时单元格里连一个可见的字都摆不下（还要占掉内边距），网格只会把内容切成碎片，
     * 此时整张表退回代码块降级（见 {@link #tableAsCodeBlock}）。
     */
    private static final int MIN_COLUMN_WIDTH = 3;

    /** 截断标记。 */
    static final String ELLIPSIS = "\u2026";

    /** 嵌套深度上限：病态输入（几百层引用或强调）会既吃栈又产出一屏无意义的缩进。 */
    private static final int MAX_DEPTH = 8;

    /**
     * 按 markdown 渲染的字符数上限。
     * <p>
     * 实测（JDK 1.8 + commonmark 0.21.0，解析 + 遍历全树）：256 KB 约 <b>41 ms</b>，16 KB 约 3 ms。
     * 而流式期间每个脏帧都要重投影（见 {@code ChatState.refreshProjection}），帧预算只有 40 ms——
     * 一份超大回答会把帧率直接拖下去。因此超过这个长度时，<b>更早的部分退回纯文本渲染、
     * 只对尾部窗口做 markdown 解析</b>：内容一条不藏，代价是超大回答的头部丢掉样式
     * （它在回合收敛落库后会重新按 markdown 渲染一次）。
     */
    static final int PARSE_MAX_CHARS = 32 * 1024;

    private MarkdownRenderer() {
    }

    /**
     * 渲染一段 markdown。
     *
     * @param markdown  markdown 原文，可为 {@code null}
     * @param width     正文可用列数（<b>不含</b>消息缩进），小于 1 时按 1 处理
     * @param baseStyle 基础样式，不可为 {@code null}；块级样式都以它为底
     * @return 视觉行列表，保证非 {@code null}；内容为空时返回空列表
     */
    public static List<VisualLine> render(String markdown, int width, Style baseStyle) {
        String text = ControlChars.strip(markdown);
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        Ctx ctx = new Ctx(Math.max(1, width), baseStyle);
        String tail = text;
        if (text.length() > PARSE_MAX_CHARS) {
            int cut = boundaryBefore(text, text.length() - PARSE_MAX_CHARS);
            // 头部按纯文本铺出：它已经定型、不会再变，没必要每帧为它付一次解析
            emitPlain(ctx, text.substring(0, cut));
            ctx.spaced = true;
            tail = text.substring(cut);
        }
        Node document = parse(tail);
        if (document == null) {
            emitPlain(ctx, tail);
        } else {
            ctx.spaced = true;
            blocks(document, ctx);
        }
        return ctx.out;
    }

    /**
     * 解析 markdown，失败时返回 {@code null}（由调用方退回纯文本）。
     * <p>
     * 只捕获 {@link RuntimeException}：解析器对残缺输入是宽容的，真正的异常来自实现缺陷或病态嵌套，
     * 无论哪种都不该把界面带下去。{@link Error}（如栈溢出）刻意不接——那属于「进程该处理的问题」，
     * 用 {@code catch (Throwable)} 掩盖它只会让问题更难定位。
     *
     * @param text markdown 原文
     * @return 文档根节点；解析失败返回 {@code null}
     */
    private static Node parse(String text) {
        try {
            return PARSER.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 找出不切断代理对的分界点：优先取边界之前的最后一个换行。
     *
     * @param text    原文
     * @param offset  期望的切分位置
     * @return 实际切分位置，保证落在码点边界上
     */
    private static int boundaryBefore(String text, int offset) {
        int limit = Math.max(0, Math.min(offset, text.length()));
        for (int i = limit; i > 0; i--) {
            if (text.charAt(i - 1) == '\n') {
                return i;
            }
        }
        // 整段没有换行（例如一个超长的代码块）：退到码点边界，避免把代理对切成两半
        int cut = limit;
        while (cut > 0 && cut < text.length() && Character.isLowSurrogate(text.charAt(cut))) {
            cut--;
        }
        return cut;
    }

    /**
     * 遍历若干块级节点。
     *
     * @param parent 父节点
     * @param ctx    渲染上下文
     */
    private static void blocks(Node parent, Ctx ctx) {
        // 空行只补在「本容器内部相邻的块之间」：用起点水位而不是「输出是否为空」判断，
        // 否则容器的第一个块会连同外层内容一起被隔开（引用块的首行前会莫名多一个空行）
        int mark = ctx.out.size();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (ctx.spaced && ctx.out.size() > mark
                    && !ctx.out.get(ctx.out.size() - 1).isEmpty()) {
                // markdown 源码里块之间本来就有空行，不补会让多段回答糊成一块
                ctx.out.add(VisualLine.EMPTY);
            }
            block(node, ctx);
        }
    }

    /**
     * 渲染单个块级节点。
     *
     * @param node 节点
     * @param ctx  渲染上下文
     */
    private static void block(Node node, Ctx ctx) {
        if (node instanceof Heading) {
            heading((Heading) node, ctx);
        } else if (node instanceof Paragraph) {
            paragraph((Paragraph) node, ctx);
        } else if (node instanceof BulletList || node instanceof OrderedList) {
            list((ListBlock) node, ctx);
        } else if (node instanceof BlockQuote) {
            quote((BlockQuote) node, ctx);
        } else if (node instanceof FencedCodeBlock) {
            FencedCodeBlock fenced = (FencedCodeBlock) node;
            code(fenced.getLiteral(), fenced.getInfo(), ctx);
        } else if (node instanceof IndentedCodeBlock) {
            code(((IndentedCodeBlock) node).getLiteral(), null, ctx);
        } else if (node instanceof ThematicBreak) {
            rule(ctx);
        } else if (node instanceof TableBlock) {
            table((TableBlock) node, ctx);
        } else if (node instanceof HtmlBlock) {
            emitPlain(ctx, ((HtmlBlock) node).getLiteral());
        } else if (node instanceof ListItem) {
            // 兜底：单个 ListItem 不会出现在这里，但真出现了就当普通块铺开
            blocks(node, ctx);
        } else {
            paragraph(node, ctx);
        }
    }

    /**
     * 渲染标题：加粗，一级二级再给一点强调色。
     *
     * @param node 标题节点
     * @param ctx  渲染上下文
     */
    private static void heading(Heading node, Ctx ctx) {
        Style style = node.getLevel() <= 2 ? ctx.base.bold().cyan() : ctx.base.bold();
        ctx.pendingPrefix = HEADING_PREFIX;
        emit(ctx, style, inline(node, style));
    }

    /**
     * 渲染段落（也用于任何「当段落铺开」的兜底节点）。
     *
     * @param node 段落节点
     * @param ctx  渲染上下文
     */
    private static void paragraph(Node node, Ctx ctx) {
        emit(ctx, ctx.base, inline(node, ctx.base));
    }

    /**
     * 渲染列表：每个条目一个标记，嵌套层级换个标记。
     * <p>
     * <b>嵌套靠标记的悬挂缩进表达，不靠额外缩进</b>：条目的续行对齐到标记之后（{@link LineWrapper}
     * 的续行缩进就是前缀宽度），因此下一层的标记恰好落在上一层正文那一列——
     * 层级关系由「谁的正文在哪一列」直接读出，不需要再叠一层固定缩进。
     *
     * @param list 列表节点
     * @param ctx  渲染上下文
     */
    private static void list(ListBlock list, Ctx ctx) {
        boolean ordered = list instanceof OrderedList;
        int number = ordered ? ((OrderedList) list).getStartNumber() : 0;
        int digits = ordered ? String.valueOf(number + count(list)).length() : 0;
        for (Node node = list.getFirstChild(); node != null; node = node.getNext()) {
            if (!(node instanceof ListItem)) {
                continue;
            }
            String marker = ordered
                    ? pad(String.valueOf(number), digits) + ". "
                    : BULLETS[(ctx.depth - 1) % BULLETS.length];
            number++;
            item((ListItem) node, marker, ctx);
        }
    }

    /**
     * 渲染一个列表条目。
     * <p>
     * 标记只挂在条目的<b>第一个内容行</b>上（空条目除外）：条目里除了段落还可能有代码块、
     * 嵌套列表，逐块挂标记会刷出一串孤零零的圆点。
     *
     * @param item   条目节点
     * @param marker 条目标记（含尾部空格）
     * @param ctx    渲染上下文
     */
    private static void item(ListItem item, String marker, Ctx ctx) {
        String outerPrefix = ctx.linePrefix;
        boolean outerSpaced = ctx.spaced;
        String pad = spaces(DisplayWidth.of(marker));
        ctx.spaced = false;
        ctx.depth++;
        boolean first = true;
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            // 首块用标记，其后各块对齐到标记之后
            ctx.linePrefix = first ? outerPrefix : outerPrefix + pad;
            if (first) {
                ctx.pendingPrefix = marker;
            }
            block(child, ctx);
            first = false;
        }
        if (first) {
            // 空条目：标记仍然要打出来，否则用户看不到这一项存在
            ctx.linePrefix = outerPrefix;
            ctx.pendingPrefix = marker;
            emit(ctx, ctx.base, Collections.<StyledSegment>emptyList());
        }
        ctx.depth--;
        ctx.linePrefix = outerPrefix;
        ctx.spaced = outerSpaced;
    }

    /**
     * 渲染引用块：整体加上竖线前缀并压低亮度。
     *
     * @param node 引用块节点
     * @param ctx  渲染上下文
     */
    private static void quote(BlockQuote node, Ctx ctx) {
        String outerPrefix = ctx.linePrefix;
        boolean outerSpaced = ctx.spaced;
        Style outerBase = ctx.base;
        boolean deep = ctx.quoteDepth >= MAX_DEPTH;
        if (!deep) {
            // 超深嵌套不再累积竖线：200 层引用会给出 400 列前缀，正文只剩 1 列，
            // 于是每个字占一行——病态输入不该变成一屏幕的排版垃圾
            ctx.linePrefix = outerPrefix + QUOTE_PREFIX;
            ctx.quoteDepth++;
        }
        // 引用块整体降一档亮度：它是「别人说的话」，与正文同权重会让两种声音分不开
        ctx.base = outerBase.dim();
        ctx.spaced = true;
        blocks(node, ctx);
        ctx.linePrefix = outerPrefix;
        ctx.base = outerBase;
        ctx.spaced = outerSpaced;
        if (!deep) {
            ctx.quoteDepth--;
        }
    }

    /**
     * 渲染代码块：围栏 + 逐行原样输出（不折行）。
     * <p>
     * <b>为什么不折行</b>：折行会破坏代码的缩进结构，把一段本可读的代码切成一堆碎行。
     * 超过可用列数的行按列截断并标 {@value #ELLIPSIS}，用户至少知道这里被切了。
     *
     * @param literal 代码原文，可为 {@code null}
     * @param info    围栏后的语言标签，可为 {@code null}
     * @param ctx     渲染上下文
     */
    private static void code(String literal, String info, Ctx ctx) {
        Style style = ctx.base.dim();
        String language = info == null ? "" : info.trim();
        // 首行是围栏 + 语言标签；语言标签可能带额外参数（如 ```java title=x），只取第一个词
        int space = language.indexOf(' ');
        if (space > 0) {
            language = language.substring(0, space);
        }
        raw(ctx, style, FENCE + language);
        for (String line : lines(literal)) {
            raw(ctx, style, line);
        }
        raw(ctx, style, FENCE);
    }

    /**
     * 渲染分隔线。
     *
     * @param ctx 渲染上下文
     */
    private static void rule(Ctx ctx) {
        raw(ctx, ctx.base.dim(), repeat(RULE_CHAR, ctx.width));
    }

    /**
     * 渲染表格：量出每列的宽度后画成网格，格子放不下时在列内折行。
     * <p>
     * <b>为什么现在敢做列对齐</b>：对齐的唯一依据是「这个码点占几列」，而它取决于终端自己的字宽表。
     * 本项目的主力输入是中英混排（中文占 2 列），{@link DisplayWidth} 已按 East Asian Width
     * 把常见字符都算准；宽度有歧义的少数码点（带变体选择符的 emoji、{@code ±} 这类）
     * 仍可能被多算或少算一列，那种表偶尔错位一格——但整体仍读得出是张表，比一律不排要好。
     * <p>
     * <b>放不下时折行、不截断</b>：表格里最长的往往是「说明」这类关键列，截断等于把最该看的内容丢掉；
     * 折行只是让表格变高，信息一条不丢（行数不设上限，与代码块同一口径）。
     * <p>
     * <b>实在放不下时退回代码块</b>：列数过多，多到每列连 {@link #MIN_COLUMN_WIDTH} 列都分不到时，
     * 网格会把每个格子切成碎片，不如退回等宽代码块——至少每个格子的原文看得清。
     *
     * @param node 表格节点
     * @param ctx  渲染上下文
     */
    private static void table(TableBlock node, Ctx ctx) {
        String prefix = ctx.takePrefix();
        Style grid = ctx.base.dim();
        List<List<Cell>> head = new ArrayList<List<Cell>>();
        List<List<Cell>> body = new ArrayList<List<Cell>>();
        for (Node section = node.getFirstChild(); section != null; section = section.getNext()) {
            boolean header = section instanceof TableHead;
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (row instanceof TableRow) {
                    (header ? head : body).add(cells((TableRow) row, ctx.base, header));
                }
            }
        }
        int columns = Math.max(columnCount(head), columnCount(body));
        int[] natural = new int[columns];
        measure(head, natural);
        measure(body, natural);
        int budget = ctx.width - DisplayWidth.of(prefix)
                - GRID_OVERHEAD_PER_COLUMN * columns - GRID_OVERHEAD_EDGE;
        if (columns == 0 || budget < columns * MIN_COLUMN_WIDTH) {
            tableAsCodeBlock(ctx, prefix, grid, head, body);
            return;
        }
        int[] widths = allocate(natural, budget);
        gridLine(ctx, prefix, grid, widths, '\u250c', '\u252c', '\u2510');
        for (List<Cell> row : head) {
            gridRow(ctx, prefix, grid, widths, row);
        }
        if (!head.isEmpty() && !body.isEmpty()) {
            // 表头与表体之间那道横线：没有它，「哪一行是列名」就只能靠猜
            gridLine(ctx, prefix, grid, widths, '\u251c', '\u253c', '\u2524');
        }
        for (List<Cell> row : body) {
            gridRow(ctx, prefix, grid, widths, row);
        }
        gridLine(ctx, prefix, grid, widths, '\u2514', '\u2534', '\u2518');
    }

    /**
     * 表格的降级渲染：<b>等宽代码块</b>。
     * <p>
     * 保留原有的行结构（含表头下的 {@code | --- |} 分隔行），但不承诺任何列对齐——
     * 它的价值只在「每个格子看得清」，不假装是一张排好的表。
     *
     * @param ctx    渲染上下文
     * @param prefix 行首前缀
     * @param style  样式
     * @param head   表头行
     * @param body   表体行
     */
    private static void tableAsCodeBlock(Ctx ctx, String prefix, Style style,
                                        List<List<Cell>> head, List<List<Cell>> body) {
        fixed(ctx, prefix, style, FENCE);
        for (List<Cell> row : head) {
            fixed(ctx, prefix, style, pipeRow(row));
            fixed(ctx, prefix, style, "|" + repeat(" --- |", row.size()));
        }
        for (List<Cell> row : body) {
            fixed(ctx, prefix, style, pipeRow(row));
        }
        fixed(ctx, prefix, style, FENCE);
    }

    /**
     * 把一行单元格拼成等宽文本（降级渲染用）。
     *
     * @param row 单元格列表
     * @return 形如 {@code | 甲 | 乙 |} 的文本
     */
    private static String pipeRow(List<Cell> row) {
        StringBuilder sb = new StringBuilder("|");
        for (Cell cell : row) {
            sb.append(' ').append(textOf(cell.content, 0)).append(" |");
        }
        return sb.toString();
    }

    /**
     * 取一行的单元格：内容走行内渲染规则，表头单元格整体加粗。
     *
     * @param row    行节点
     * @param base   基础样式
     * @param header 是否为表头行
     * @return 单元格列表，保证非 {@code null}
     */
    private static List<Cell> cells(TableRow row, Style base, boolean header) {
        List<Cell> cells = new ArrayList<Cell>();
        for (Node node = row.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof TableCell) {
                cells.add(new Cell((TableCell) node, header ? base.bold() : base));
            }
        }
        return cells;
    }

    /**
     * 统计若干行里最多有几个单元格（即列数）。
     *
     * @param rows 行列表
     * @return 列数
     */
    private static int columnCount(List<List<Cell>> rows) {
        int columns = 0;
        for (List<Cell> row : rows) {
            columns = Math.max(columns, row.size());
        }
        return columns;
    }

    /**
     * 量出各列的自然宽度（折行之前的内容宽度）。
     *
     * @param rows    行列表
     * @param natural 输出数组，下标即列号
     */
    private static void measure(List<List<Cell>> rows, int[] natural) {
        for (List<Cell> row : rows) {
            for (int i = 0; i < row.size() && i < natural.length; i++) {
                natural[i] = Math.max(natural[i], row.get(i).width());
            }
        }
    }

    /**
     * 把可用列数分给各列。
     * <p>
     * <b>水位法</b>：自然宽度高于水位的列一律压到水位，其余保持原宽。水位取「压完之后总和仍不超预算」
     * 的最大值（二分求得），于是窄列不会被无谓拉宽，宽列按超出水位的多少让出空间。
     * <p>
     * 水位是离散的，压完之后通常还剩一点零头，把它补回被压得最狠的那几列：可用宽度就这么多，
     * 多给某一列一列宽度就少折一行，不用满等于白扔。
     *
     * @param natural 各列自然宽度
     * @param budget  各列内容宽度之和的上限
     * @return 各列实际宽度，每列不小于 {@link #MIN_COLUMN_WIDTH}
     */
    private static int[] allocate(int[] natural, int budget) {
        int[] widths = natural.clone();
        int used = 0;
        for (int width : widths) {
            used += width;
        }
        if (used <= budget) {
            // 自然宽度就放得下：不拉宽也不压缩，表格因此贴着内容走
            return widths;
        }
        int low = MIN_COLUMN_WIDTH;
        int high = 0;
        for (int width : widths) {
            high = Math.max(high, width);
        }
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (usedAt(widths, mid) <= budget) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        used = 0;
        for (int i = 0; i < widths.length; i++) {
            widths[i] = Math.max(MIN_COLUMN_WIDTH, Math.min(widths[i], low));
            used += widths[i];
        }
        for (int i = 0; i < widths.length && used < budget; i++) {
            if (natural[i] > widths[i]) {
                widths[i]++;
                used++;
            }
        }
        return widths;
    }

    /**
     * 计算「所有列都压到不超过指定水位」之后的内容宽度之和。
     *
     * @param natural 各列自然宽度
     * @param level   水位
     * @return 宽度之和
     */
    private static int usedAt(int[] natural, int level) {
        int used = 0;
        for (int width : natural) {
            used += Math.max(MIN_COLUMN_WIDTH, Math.min(width, level));
        }
        return used;
    }

    /**
     * 画一条表格横线。
     *
     * @param ctx    渲染上下文
     * @param prefix 行首前缀
     * @param style  样式
     * @param widths 各列宽度
     * @param left   左端字符（{@code ┌} / {@code ├} / {@code └}）
     * @param joint  列间交叉字符（{@code ┬} / {@code ┼} / {@code ┴}）
     * @param right  右端字符（{@code ┐} / {@code ┤} / {@code ┘}）
     */
    private static void gridLine(Ctx ctx, String prefix, Style style, int[] widths,
                                 char left, char joint, char right) {
        StringBuilder sb = new StringBuilder();
        sb.append(left);
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                sb.append(joint);
            }
            sb.append(repeat(RULE_CHAR, widths[i] + CELL_PADDING * 2));
        }
        sb.append(right);
        fixed(ctx, prefix, style, sb.toString());
    }

    /**
     * 渲染一行表格：每格先按列宽折行，再按本行最高的那个格子把矮格子补齐。
     *
     * @param ctx    渲染上下文
     * @param prefix 行首前缀
     * @param grid   网格字符样式
     * @param widths 各列宽度
     * @param row    本行单元格
     */
    private static void gridRow(Ctx ctx, String prefix, Style grid, int[] widths, List<Cell> row) {
        List<List<List<StyledSegment>>> wrapped = new ArrayList<List<List<StyledSegment>>>(widths.length);
        int height = 1;
        for (int i = 0; i < widths.length; i++) {
            List<StyledSegment> content = i < row.size()
                    ? row.get(i).content : Collections.<StyledSegment>emptyList();
            List<List<StyledSegment>> lines = wrapCell(content, widths[i]);
            wrapped.add(lines);
            height = Math.max(height, lines.size());
        }
        for (int k = 0; k < height; k++) {
            List<StyledSegment> segments = new ArrayList<StyledSegment>(widths.length * 4 + 2);
            segments.add(new StyledSegment(prefix, grid));
            for (int i = 0; i < widths.length; i++) {
                Cell cell = i < row.size() ? row.get(i) : null;
                Style style = cell == null ? ctx.base : cell.style;
                List<StyledSegment> line = k < wrapped.get(i).size()
                        ? wrapped.get(i).get(k) : Collections.<StyledSegment>emptyList();
                int pad = widths[i] - DisplayWidth.of(textOf(line, 0));
                int left = cell == null ? 0 : alignPad(pad, cell.alignment);
                segments.add(new StyledSegment(TABLE_BAR, grid));
                segments.add(new StyledSegment(spaces(CELL_PADDING + left), style));
                segments.addAll(line);
                segments.add(new StyledSegment(spaces(CELL_PADDING + pad - left), style));
            }
            segments.add(new StyledSegment(TABLE_BAR, grid));
            ctx.out.add(new VisualLine(segments));
        }
    }

    /**
     * 把一个单元格的内容按列宽折行。
     *
     * @param content 单元格内容
     * @param width   列宽
     * @return 每行的样式段列表，至少一个元素（空内容也占一行）
     */
    private static List<List<StyledSegment>> wrapCell(List<StyledSegment> content, int width) {
        List<List<StyledSegment>> lines = new ArrayList<List<StyledSegment>>();
        for (VisualLine line : LineWrapper.wrap(new StyledSegment("", Style.EMPTY), content, width)) {
            List<StyledSegment> segments = new ArrayList<StyledSegment>(line.getSegments().size());
            for (StyledSegment segment : line.getSegments()) {
                // 换行器会在行首放一个空前缀段，网格自己负责边界，把它丢掉
                if (!segment.isEmpty()) {
                    segments.add(segment);
                }
            }
            lines.add(segments);
        }
        return lines;
    }

    /**
     * 计算一行单元格内容左侧要补多少空格。
     *
     * @param pad       该行剩余列数
     * @param alignment 对齐方式，可为 {@code null}（按左对齐处理）
     * @return 左侧补白列数
     */
    private static int alignPad(int pad, TableCell.Alignment alignment) {
        if (alignment == TableCell.Alignment.RIGHT) {
            return pad;
        }
        if (alignment == TableCell.Alignment.CENTER) {
            return pad / 2;
        }
        return 0;
    }

    /**
     * 取一个单元格的可见内容。
     * <p>
     * 走的是行内渲染规则（行内代码着色、加粗、删除线都保留），但内部换行压成空格：
     * 单元格在网格里是「一格」，换行由列宽决定，不由源码决定。
     *
     * @param cell  单元格节点
     * @param style 基础样式
     * @return 样式段列表，保证非 {@code null}
     */
    private static List<StyledSegment> cellContent(Node cell, Style style) {
        List<StyledSegment> rendered = inline(cell, style);
        List<StyledSegment> flattened = new ArrayList<StyledSegment>(rendered.size());
        for (StyledSegment segment : rendered) {
            flattened.add(new StyledSegment(oneLine(segment.getText()), segment.getStyle()));
        }
        return trim(flattened);
    }

    /**
     * 去掉内容两端的空白：内边距由网格统一提供，单元格再自带空白只会让列宽虚胖。
     *
     * @param segments 样式段列表
     * @return 去空白后的样式段列表
     */
    private static List<StyledSegment> trim(List<StyledSegment> segments) {
        List<StyledSegment> out = new ArrayList<StyledSegment>(segments.size());
        boolean started = false;
        for (StyledSegment segment : segments) {
            String text = segment.getText();
            if (!started) {
                text = stripLeading(text);
                if (text.isEmpty()) {
                    continue;
                }
                started = true;
            }
            out.add(new StyledSegment(text, segment.getStyle()));
        }
        while (!out.isEmpty()) {
            StyledSegment last = out.get(out.size() - 1);
            String text = stripTrailing(last.getText());
            if (text.isEmpty()) {
                out.remove(out.size() - 1);
                continue;
            }
            out.set(out.size() - 1, new StyledSegment(text, last.getStyle()));
            break;
        }
        return out;
    }

    /**
     * 去掉开头的空白。
     *
     * @param text 文本
     * @return 结果文本
     */
    private static String stripLeading(String text) {
        int i = 0;
        while (i < text.length() && isBlank(text.charAt(i))) {
            i++;
        }
        return text.substring(i);
    }

    /**
     * 去掉结尾的空白。
     *
     * @param text 文本
     * @return 结果文本
     */
    private static String stripTrailing(String text) {
        int i = text.length();
        while (i > 0 && isBlank(text.charAt(i - 1))) {
            i--;
        }
        return text.substring(0, i);
    }

    /**
     * 判断字符是否算空白（单元格修剪用）。
     *
     * @param c 字符
     * @return 空格或制表符返回 {@code true}
     */
    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    /**
     * 摊平后的一个表格单元格：内容 + 内容样式 + 对齐方式。
     * <p>
     * 内容在构造时就渲染好并被多行复用（一行网格要为每一行单元格反复取内容），
     * 因此这里存的是结果而不是节点。
     */
    private static final class Cell {

        /** 单元格内容（行内规则已渲染，内部换行已压成空格）。 */
        private final List<StyledSegment> content;

        /** 单元格基准样式（表头为加粗），内边距与填充用它铺。 */
        private final Style style;

        /** 对齐方式，可为 {@code null}（按左对齐处理）。 */
        private final TableCell.Alignment alignment;

        /**
         * 构造单元格。
         *
         * @param cell  单元格节点
         * @param style 基准样式
         */
        Cell(TableCell cell, Style style) {
            this.content = cellContent(cell, style);
            this.style = style;
            this.alignment = cell.getAlignment();
        }

        /**
         * 取内容的自然宽度（折行之前）。
         *
         * @return 列数
         */
        int width() {
            return DisplayWidth.of(textOf(content, 0));
        }
    }

    /**
     * 渲染一行不折行的文本，行首前缀取当前上下文（代码块、围栏、分隔线用）。
     *
     * @param ctx   渲染上下文
     * @param style 样式
     * @param text  文本，可为 {@code null}
     */
    private static void raw(Ctx ctx, Style style, String text) {
        fixed(ctx, ctx.takePrefix(), style, text);
    }

    /**
     * 渲染一行不折行的文本，行首前缀由调用方给定（表格网格要按同一个前缀铺多行）。
     *
     * @param ctx    渲染上下文
     * @param prefix 行首前缀，不可为 {@code null}
     * @param style  样式
     * @param text   文本，可为 {@code null}
     */
    private static void fixed(Ctx ctx, String prefix, Style style, String text) {
        String content = text == null ? "" : text;
        String fitted = truncate(content, Math.max(1, ctx.width - DisplayWidth.of(prefix)));
        List<StyledSegment> segments = new ArrayList<StyledSegment>(2);
        segments.add(new StyledSegment(prefix, style));
        if (!fitted.isEmpty()) {
            segments.add(new StyledSegment(fitted, style));
        }
        ctx.out.add(new VisualLine(segments));
    }

    /**
     * 用一个块级元素铺出一段文本，按宽度换行。
     *
     * @param ctx   渲染上下文
     * @param style 样式
     * @param body  正文样式段
     */
    private static void emit(Ctx ctx, Style style, List<StyledSegment> body) {
        ctx.out.addAll(LineWrapper.wrap(new StyledSegment(ctx.takePrefix(), style), body, ctx.width));
    }

    /**
     * 把纯文本按宽度铺成视觉行（解析失败与超大回答头部的退化路径）。
     *
     * @param ctx  渲染上下文
     * @param text 原文，可为 {@code null}
     */
    private static void emitPlain(Ctx ctx, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        emit(ctx, ctx.base, Collections.singletonList(new StyledSegment(text, ctx.base)));
    }

    /**
     * 收集一个块级元素的全部行内内容。
     *
     * @param parent 父节点
     * @param style  基础样式
     * @return 样式段列表，保证非 {@code null}
     */
    private static List<StyledSegment> inline(Node parent, Style style) {
        List<StyledSegment> out = new ArrayList<StyledSegment>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            inline(node, style, out, 0);
        }
        return out;
    }

    /**
     * 渲染一个行内节点。
     * <p>
     * 样式由外层向内层叠加（{@code base → 强调 → 加粗}）：嵌套强调因此会既有斜体又有加粗，
     * 而不是内层把外层的样式覆盖掉。
     *
     * @param node  节点
     * @param style 当前样式
     * @param out   输出列表
     * @param depth 递归深度，超过 {@link #MAX_DEPTH} 后停止展开嵌套
     */
    private static void inline(Node node, Style style, List<StyledSegment> out, int depth) {
        // 叶子节点先处理：深度上限只该拦「继续往下叠加样式」，不该把文本一起丢掉。
        // （实测：100 个 * 会形成 50 层嵌套强调，拦早了整段文字会凭空消失）
        if (node instanceof Text) {
            add(out, ((Text) node).getLiteral(), style);
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            // 普通换行在 CommonMark 里就是一个空格；硬换行（行尾两个空格 / 反斜杠）保留为真换行
            add(out, node instanceof HardLineBreak ? "\n" : " ", style);
        } else if (node instanceof Code) {
            add(out, oneLine(((Code) node).getLiteral()), style.yellow());
        } else if (node instanceof HtmlInline) {
            add(out, ((HtmlInline) node).getLiteral(), style);
        } else if (depth > MAX_DEPTH) {
            // 病态嵌套：不再为更深层叠加样式，但文本必须继续取出来
            children(node, style, out, depth);
        } else if (node instanceof Emphasis) {
            children(node, style.italic(), out, depth);
        } else if (node instanceof StrongEmphasis) {
            children(node, style.bold(), out, depth);
        } else if (node instanceof Strikethrough) {
            children(node, style.crossedOut(), out, depth);
        } else if (node instanceof Link) {
            link((Link) node, style, out, depth);
        } else if (node instanceof Image) {
            image((Image) node, style, out);
        } else {
            children(node, style, out, depth);
        }
    }

    /**
     * 渲染链接：显示文本，地址以暗色追加在括号里。
     * <p>
     * <b>不输出 OSC 8 超链接</b>：地址是模型生成的不可信输入，而 OSC 8 会把地址原样写进转义序列；
     * 且不支持该序列的终端会把它当普通字符打印出来。写成可见文本则任何终端都不会出错。
     *
     * @param node  链接节点
     * @param style 当前样式
     * @param out   输出列表
     * @param depth 递归深度
     */
    private static void link(Link node, Style style, List<StyledSegment> out, int depth) {
        String url = oneLine(node.getDestination());
        int before = out.size();
        children(node, style, out, depth);
        String label = textOf(out, before);
        if (label.trim().isEmpty()) {
            // 没有显示文本（如 [](url)）：直接把地址当文本，否则这一处会变成空白
            add(out, url, style);
            return;
        }
        if (url.isEmpty() || url.equals(label)) {
            // 自动链接（<http://x>）的显示文本就是地址本身，追一遍只是重复
            return;
        }
        add(out, " (" + url + ")", style.dim());
    }

    /**
     * 渲染图片：原样显示源码（不请求、不解释）。
     *
     * @param node  图片节点
     * @param style 当前样式
     * @param out   输出列表
     */
    private static void image(Image node, Style style, List<StyledSegment> out) {
        add(out, "![" + text(node) + "](" + oneLine(node.getDestination()) + ")", style);
    }

    /**
     * 递归渲染子节点。
     *
     * @param node  父节点
     * @param style 当前样式
     * @param out   输出列表
     * @param depth 递归深度
     */
    private static void children(Node node, Style style, List<StyledSegment> out, int depth) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            inline(child, style, out, depth + 1);
        }
    }

    /**
     * 追加一段文本到输出列表。
     *
     * @param out   输出列表
     * @param text  文本，{@code null} 或空串忽略
     * @param style 样式
     */
    private static void add(List<StyledSegment> out, String text, Style style) {
        if (text == null || text.isEmpty()) {
            return;
        }
        out.add(new StyledSegment(text, style));
    }

    /**
     * 拼接从指定下标起的文本段。
     *
     * @param segments 样式段列表
     * @param from     起始下标
     * @return 纯文本
     */
    private static String textOf(List<StyledSegment> segments, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < segments.size(); i++) {
            sb.append(segments.get(i).getText());
        }
        return sb.toString();
    }

    /**
     * 递归拼接一个节点下的全部文本（用于表格单元格、图片替代文本）。
     *
     * @param node 节点
     * @return 纯文本，保证非 {@code null}
     */
    private static String text(Node node) {
        List<StyledSegment> out = new ArrayList<StyledSegment>();
        children(node, Style.EMPTY, out, 0);
        return textOf(out, 0).replace("\n", " ");
    }

    /**
     * 统计直接子节点个数（表格列数、有序列表项数）。
     *
     * @param node 节点
     * @return 子节点个数
     */
    private static int count(Node node) {
        int total = 0;
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            total++;
        }
        return total;
    }

    /**
     * 把多行字面量切成行。
     *
     * @param literal 字面量，可为 {@code null}
     * @return 行列表，保证非 {@code null}
     */
    private static List<String> lines(String literal) {
        if (literal == null || literal.isEmpty()) {
            return Collections.emptyList();
        }
        // 代码块字面量以换行结尾，直接切会多出一条空行（视觉上是代码块后面凭空多一行）
        String text = literal.endsWith("\n") ? literal.substring(0, literal.length() - 1) : literal;
        return Arrays.asList(text.split("\n", -1));
    }

    /**
     * 把内部换行压成空格（行内代码与地址不允许跨行）。
     *
     * @param text 文本，可为 {@code null}
     * @return 单行文本，原值为 {@code null} 时返回空串
     */
    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * 按列数截断文本。
     *
     * @param text  文本，保证非 {@code null}
     * @param width 可用列数，小于 1 时按 1 处理
     * @return 截断后的文本；未超宽时原样返回
     */
    static String truncate(String text, int width) {
        int limit = Math.max(1, width);
        if (DisplayWidth.of(text) <= limit) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int i = 0;
        int reserve = DisplayWidth.of(ELLIPSIS);
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int codePointWidth = DisplayWidth.ofCodePoint(codePoint);
            if (used + codePointWidth + reserve > limit) {
                break;
            }
            sb.appendCodePoint(codePoint);
            used += codePointWidth;
            i += Character.charCount(codePoint);
        }
        return sb.append(ELLIPSIS).toString();
    }

    /**
     * 生成指定列数的空白。
     *
     * @param columns 列数
     * @return 空白字符串，列数小于 1 时返回空串
     */
    private static String spaces(int columns) {
        return repeat(" ", columns);
    }

    /**
     * 重复一个字符串若干次。
     *
     * @param unit  重复单元
     * @param times 次数
     * @return 重复结果，次数小于 1 时返回空串
     */
    private static String repeat(String unit, int times) {
        if (times <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    /**
     * 左补空格到指定宽度（有序列表的编号右对齐）。
     *
     * @param text  文本
     * @param width 目标宽度（以字符数计，编号都是 ASCII）
     * @return 补齐后的文本
     */
    private static String pad(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return repeat(" ", width - text.length()) + text;
    }

    /**
     * 渲染上下文：一次渲染的可变状态。
     * <p>
     * 用一个可变对象而不是层层传参：块级渲染要往同一份输出里追加，而「当前缩进前缀」「当前基础样式」
     * 「嵌套层数」这几项在递归里都要临时替换再还原，散成参数会让每个方法都挂一长串参数。
     */
    private static final class Ctx {

        /** 正文可用列数。 */
        private final int width;

        /** 输出行。 */
        private final List<VisualLine> out = new ArrayList<VisualLine>();

        /** 当前基础样式（引用块等会临时压低它）。 */
        private Style base;

        /** 当前缩进前缀（引用块竖线、列表悬挂缩进累积而成）。 */
        private String linePrefix = "";

        /** 只作用于下一条输出行的额此前缀（列表标记、标题标记）。 */
        private String pendingPrefix = "";

        /** 列表嵌套层数，1 表示最外层。 */
        private int depth = 1;

        /** 引用块嵌套层数，用于限制竖线前缀的累积深度。 */
        private int quoteDepth;

        /** 块之间是否补空行（列表条目内为 {@code false}）。 */
        private boolean spaced;

        /**
         * 构造上下文。
         *
         * @param width 正文可用列数
         * @param base  基础样式
         */
        Ctx(int width, Style base) {
            this.width = width;
            this.base = base;
        }

        /**
         * 取出本行的行前缀并清空一次性前缀。
         *
         * @return 行前缀，保证非 {@code null}
         */
        String takePrefix() {
            String prefix = pendingPrefix.isEmpty() ? linePrefix : linePrefix + pendingPrefix;
            pendingPrefix = "";
            return prefix;
        }
    }
}
