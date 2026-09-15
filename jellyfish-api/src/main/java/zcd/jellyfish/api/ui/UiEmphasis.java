package zcd.jellyfish.api.ui;

/**
 * 界面文本的语义强调档位。
 * <p>
 * <b>为什么只给档位、不给颜色</b>：{@code jellyfish-api} 是零依赖的，而颜色/粗体/斜体都是渲染引擎
 * （TamboUI 的 {@code Style}）的概念。若在这里暴露 {@code Style}，插件就要编译期依赖渲染引擎，
 * 而 PF4J 的插件类加载器是「子优先」的——插件自带的渲染引擎类与内核那份不是同一个 {@code Class}，
 * 回传必然 {@code ClassCastException}。因此插件只说<b>语义</b>（这段文本是提示、是警告还是错误），
 * 具体长什么样由外壳唯一的映射点（{@code UiRender}）决定。
 * <p>
 * <b>档位是语义而不是外观</b>：{@code ACCENT} 的含义是「需要被扫到的运行态信息」，
 * 不是「青色」；外壳换主题时不需要改任何插件。
 *
 * @author zcd
 */
public enum UiEmphasis {

    /** 常规正文。 */
    NORMAL,

    /** 次要信息（轨迹、注释、占位），应比正文更不抢眼。 */
    DIM,

    /** 强调信息（关键指标、当前状态），应比正文更抢眼。 */
    ACCENT,

    /** 警示（配置缺失、降级、用法可疑），不是错误。 */
    WARN,

    /** 错误（读取失败、功能不可用）。 */
    ERROR
}
