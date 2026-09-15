package zcd.jellyfish.tui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.infra.command.CommandInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandCompletion} 的单元测试。
 * <p>
 * 重点锁住三件最容易出错的事：激活条件刻意很窄（一有空白就收起）、
 * 别名也参与前缀匹配、以及 {@code Esc} 收起后不得在下一帧又弹回来。
 *
 * @author zcd
 */
@DisplayName("CommandCompletion 命令补全判定")
class CommandCompletionTest {

    /** 被测对象。 */
    private CommandCompletion completion;

    /** 固定命令清单。 */
    private List<CommandInfo> available;

    @BeforeEach
    void setUp() {
        completion = new CommandCompletion();
        available = Arrays.asList(
                command("help", "查看帮助", null),
                command("new", "新建会话", null),
                command("model", "切换模型", "<provider/model>"),
                command("mode", "切换权限模式", "<plan|default>"),
                command("resume", "恢复会话", "<sessionId>", "r"));
    }

    @Test
    @DisplayName("不以 / 开头时不激活补全")
    void refresh_should_beInactive_when_notCommandPrefix() {
        completion.refresh("hello", available);

        assertFalse(completion.isActive());
        assertTrue(completion.getCandidates().isEmpty());
    }

    @Test
    @DisplayName("只有 / 时激活并列出全部命令")
    void refresh_should_listAllCommands_when_onlyPrefix() {
        completion.refresh("/", available);

        assertTrue(completion.isActive());
        assertEquals(5, completion.getCandidates().size());
        assertEquals("/help", completion.accept());
    }

    @Test
    @DisplayName("命令词后出现空白（开始给参数）时收起面板")
    void refresh_should_beInactive_when_tokenFollowedBySpace() {
        completion.refresh("/mode plan", available);

        assertFalse(completion.isActive());
    }

    @Test
    @DisplayName("多行输入（含换行）时不激活")
    void refresh_should_beInactive_when_multiline() {
        completion.refresh("/help\nmore", available);

        assertFalse(completion.isActive());
    }

    @Test
    @DisplayName("按命令名前缀过滤")
    void refresh_should_filterByNamePrefix_when_tokenMatches() {
        completion.refresh("/mo", available);

        List<String> names = names();
        assertEquals(Arrays.asList("model", "mode"), names);
    }

    @Test
    @DisplayName("别名也参与前缀匹配")
    void refresh_should_matchAlias_when_tokenMatchesAlias() {
        completion.refresh("/r", available);

        assertEquals(Collections.singletonList("resume"), names());
    }

    @Test
    @DisplayName("前缀匹配不区分大小写")
    void refresh_should_matchIgnoreCase() {
        completion.refresh("/HE", available);

        assertEquals(Collections.singletonList("help"), names());
    }

    @Test
    @DisplayName("没有匹配时仍处于激活态，但候选为空")
    void refresh_should_stayActive_when_noMatch() {
        completion.refresh("/zzz", available);

        assertTrue(completion.isActive());
        assertTrue(completion.getCandidates().isEmpty());
        assertNull(completion.accept());
    }

    @Test
    @DisplayName("命令词变化时选中项复位到第一条")
    void refresh_should_resetSelection_when_tokenChanges() {
        completion.refresh("/", available);
        completion.moveDown();
        completion.moveDown();
        assertEquals(2, completion.getSelectedIndex());

        completion.refresh("/mo", available);

        assertEquals(0, completion.getSelectedIndex());
    }

    @Test
    @DisplayName("下移到底后回到第一条")
    void moveDown_should_wrapAround() {
        completion.refresh("/mo", available);

        completion.moveDown();
        assertEquals(1, completion.getSelectedIndex());
        completion.moveDown();
        assertEquals(0, completion.getSelectedIndex());
    }

    @Test
    @DisplayName("上移到第一条后再上移回到最后一条")
    void moveUp_should_wrapAround() {
        completion.refresh("/mo", available);

        completion.moveUp();
        assertEquals(1, completion.getSelectedIndex());
    }

    @Test
    @DisplayName("无候选时上下移动不越界")
    void moveDown_should_doNothing_when_noCandidates() {
        completion.refresh("/zzz", available);

        completion.moveDown();
        completion.moveUp();

        assertEquals(0, completion.getSelectedIndex());
    }

    @Test
    @DisplayName("接受无用法命令时只回填命令名")
    void accept_should_returnNameOnly_when_noUsage() {
        completion.refresh("/he", available);

        assertEquals("/help", completion.accept());
    }

    @Test
    @DisplayName("接受带用法命令时补一个空格，方便接着打参数")
    void accept_should_appendSpace_when_usagePresent() {
        completion.refresh("/model", available);

        assertEquals("/model ", completion.accept());
    }

    @Test
    @DisplayName("accept 回填的命令名用规范名而不是别名")
    void accept_should_returnCanonicalName_when_matchedByAlias() {
        completion.refresh("/r", available);

        assertEquals("/resume ", completion.accept());
    }

    @Test
    @DisplayName("Esc 收起后，命令词没变就不再弹出")
    void refresh_should_stayClosed_when_dismissedTokenUnchanged() {
        completion.refresh("/mo", available);
        completion.dismiss();
        assertFalse(completion.isActive());

        completion.refresh("/mo", available);

        assertFalse(completion.isActive());
    }

    @Test
    @DisplayName("命令词再变化后允许重新弹出")
    void refresh_should_reopen_when_tokenChangesAfterDismiss() {
        completion.refresh("/mo", available);
        completion.dismiss();

        completion.refresh("/mod", available);

        assertTrue(completion.isActive());
    }

    @Test
    @DisplayName("离开补全上下文后再输入同一命令词应能重新弹出")
    void refresh_should_reopen_when_contextLeftAndReentered() {
        completion.refresh("/mo", available);
        completion.dismiss();
        completion.refresh("hello", available);

        completion.refresh("/mo", available);

        assertTrue(completion.isActive());
    }

    @Test
    @DisplayName("命令清单为 null 或空时仍可激活但不给候选")
    void refresh_should_tolerateEmptyCommandList() {
        completion.refresh("/", null);
        assertTrue(completion.getCandidates().isEmpty());

        completion.refresh("/", Collections.<CommandInfo>emptyList());
        assertTrue(completion.getCandidates().isEmpty());
    }

    /**
     * 取当前候选的命令名列表。
     *
     * @return 命令名列表
     */
    private List<String> names() {
        List<String> names = new ArrayList<String>();
        for (CommandInfo info : completion.getCandidates()) {
            names.add(info.getName());
        }
        return names;
    }

    /**
     * 构造带名片的命令清单项。
     *
     * @param name    命令名
     * @param summary 说明
     * @param usage   用法片段，可为 {@code null}
     * @param aliases 别名
     * @return 清单项
     */
    private static CommandInfo command(String name, String summary, String usage, String... aliases) {
        List<String> aliasList = aliases.length == 0 ? null : Arrays.asList(aliases);
        return new CommandInfo(name, new CommandDescriptor(summary, usage, aliasList));
    }
}
