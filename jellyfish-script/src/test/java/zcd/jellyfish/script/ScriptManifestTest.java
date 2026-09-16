package zcd.jellyfish.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.script.codec.ExtensionCodecs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚本清单解析与校验的单元测试。
 * <p>
 * 这个类的用例绝大多数是<b>「必须报错」</b>，因为清单是注册的唯一来源：一次静默的解析失败等于
 * 「工具凭空消失、没有任何线索」。最典型的一条是未知键——
 * {@code commands} 写成 {@code command} 后，清单里就一条命令都没有了，而不报错的实现会让作者
 * 完全无从下手，所以这里逐个钉住。
 *
 * @author zcd
 */
@DisplayName("脚本清单解析")
class ScriptManifestTest {

    /** 测试用目录名，作为 id 缺省值。 */
    private static final String FALLBACK_ID = "jira";

    @Test
    @DisplayName("完整清单应解析出全部声明")
    void parse_should_readEveryDeclaration_when_manifestIsComplete() {
        ScriptManifest manifest = parse("{"
                + "\"id\":\"jira\",\"entry\":\"main.py\","
                + "\"tools\":[{\"name\":\"jira_issue\",\"description\":\"读或改\","
                + "\"parameters\":{\"key\":{\"type\":\"string\"}},\"required\":[\"key\"],\"readOnly\":true}],"
                + "\"commands\":[{\"name\":\"jira\",\"descriptor\":{\"summary\":\"操作\",\"usage\":\"<key>\","
                + "\"aliases\":[\"j\"]},\"hasOptions\":true}],"
                + "\"contributions\":[\"prompt\",\"status_line\"],"
                + "\"events\":[\"SessionCreatedEvent\"]}");

        assertEquals("jira", manifest.id());
        assertEquals("main.py", manifest.entry());
        assertEquals(1, manifest.tools().size());
        assertEquals("jira_issue", manifest.tools().get(0).name());
        assertTrue(manifest.tools().get(0).readOnly());
        assertEquals(1, manifest.tools().get(0).required().size());
        assertEquals(1, manifest.commands().size());
        assertTrue(manifest.commands().get(0).hasOptions());
        assertEquals("操作", manifest.commands().get(0).descriptor().getSummary());
        assertEquals(2, manifest.contributions().size());
        assertTrue(manifest.events().contains("SessionCreatedEvent"));
    }

    @Test
    @DisplayName("订阅不可订阅的事件名应被拒绝，且报错里带上可订阅的名字")
    void parse_should_reject_when_eventNameUnknown() {
        // 运行期这条路的失败是静默的（处理器永远不执行），因此必须在清单期拒绝：
        // 报错里带上可订阅的事件名，是因为写错的人多半只是拼错或记错了全名
        zcd.jellyfish.api.JellyfishException error = org.junit.jupiter.api.Assertions.assertThrows(
                zcd.jellyfish.api.JellyfishException.class,
                () -> parse("{\"entry\":\"main.py\",\"events\":[\"SessionCreateEvent\"]}"));

        assertTrue(error.getMessage().contains("不可订阅"), error.getMessage());
        assertTrue(error.getMessage().contains("SessionCreatedEvent"), error.getMessage());
    }

    @Test
    @DisplayName("订阅重复的事件名应被拒绝")
    void parse_should_reject_when_eventNameDuplicated() {
        org.junit.jupiter.api.Assertions.assertThrows(zcd.jellyfish.api.JellyfishException.class,
                () -> parse("{\"entry\":\"main.py\",\"events\":[\"SessionCreatedEvent\",\"SessionCreatedEvent\"]}"));
    }

    @Test
    @DisplayName("id 缺失时应退回目录名")
    void parse_should_fallBackToDirectoryName_when_idIsAbsent() {
        assertEquals(FALLBACK_ID, parse("{\"entry\":\"main.py\"}").id());
    }

    @Test
    @DisplayName("id 含 owner 命名空间分隔符或空白时应报错")
    void parse_should_rejectInvalidId_when_idBreaksOwnerNamespace() {
        assertThrows(JellyfishException.class, () -> parse("{\"id\":\"a::b\",\"entry\":\"main.py\"}"));
        assertThrows(JellyfishException.class, () -> parse("{\"id\":\"a b\",\"entry\":\"main.py\"}"));
        assertThrows(JellyfishException.class, () -> parse("{\"id\":\"a/b\",\"entry\":\"main.py\"}"));
    }

    @Test
    @DisplayName("未知键必须报错，并在消息里列出允许的键名")
    void parse_should_rejectUnknownKey_when_keyIsMisspelled() {
        // commands 写成 command：不报错就会出现「命令静默消失、毫无线索」
        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> parse("{\"entry\":\"main.py\",\"command\":[]}"));

        assertTrue(failure.getMessage().contains("command"), failure.getMessage());
        assertTrue(failure.getMessage().contains("commands"), failure.getMessage());
    }

    @Test
    @DisplayName("嵌套对象里的未知键同样必须报错")
    void parse_should_rejectUnknownKey_when_nestedKeyIsMisspelled() {
        assertThrows(JellyfishException.class, () -> parse("{\"entry\":\"main.py\","
                + "\"tools\":[{\"name\":\"t\",\"readonly\":true}]}"));
        assertThrows(JellyfishException.class, () -> parse("{\"entry\":\"main.py\","
                + "\"commands\":[{\"name\":\"c\",\"descriptor\":{\"summery\":\"x\"}}]}"));
    }

    @Test
    @DisplayName("entry 缺失或为空白应报错")
    void parse_should_rejectMissingEntry_when_entryIsAbsent() {
        assertThrows(JellyfishException.class, () -> parse("{\"id\":\"jira\"}"));
        assertThrows(JellyfishException.class, () -> parse("{\"id\":\"jira\",\"entry\":\"  \"}"));
    }

    @Test
    @DisplayName("工具名重复应报错，并指出是第几个条目")
    void parse_should_rejectDuplicateToolName_when_twoToolsShareName() {
        JellyfishException failure = assertThrows(JellyfishException.class, () -> parse(
                "{\"entry\":\"main.py\",\"tools\":[{\"name\":\"t\"},{\"name\":\"t\"}]}"));

        assertTrue(failure.getMessage().contains("tools[1]"), failure.getMessage());
    }

    @Test
    @DisplayName("工具字段类型不对应报错")
    void parse_should_rejectWrongFieldType_when_readOnlyIsNotBoolean() {
        assertThrows(JellyfishException.class,
                () -> parse("{\"entry\":\"main.py\",\"tools\":[{\"name\":\"t\",\"readOnly\":\"yes\"}]}"));
        assertThrows(JellyfishException.class,
                () -> parse("{\"entry\":\"main.py\",\"tools\":[{\"name\":\"t\",\"required\":\"key\"}]}"));
        assertThrows(JellyfishException.class,
                () -> parse("{\"entry\":\"main.py\",\"tools\":[{\"name\":\"t\",\"parameters\":[]}]}"));
    }

    @Test
    @DisplayName("readOnly 缺省为 false，只读必须是显式选择")
    void parse_should_defaultReadOnlyToFalse_when_flagIsAbsent() {
        ScriptManifest manifest = parse("{\"entry\":\"main.py\",\"tools\":[{\"name\":\"t\"}]}");

        assertFalse(manifest.tools().get(0).readOnly());
    }

    @Test
    @DisplayName("候选查询挂在未声明的命令上应报错")
    void parse_should_rejectCommandOptions_when_commandIsNotDeclared() {
        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> parse("{\"entry\":\"main.py\",\"commandOptions\":[{\"name\":\"ghost\"}]}"));

        assertTrue(failure.getMessage().contains("ghost"), failure.getMessage());
    }

    @Test
    @DisplayName("hasOptions 与 commandOptions 声明同一条命令应报错")
    void parse_should_rejectCommandOptions_when_hasOptionsAlreadyDeclaresIt() {
        JellyfishException failure = assertThrows(JellyfishException.class, () -> parse(
                "{\"entry\":\"main.py\",\"commands\":[{\"name\":\"jira\",\"hasOptions\":true}],"
                        + "\"commandOptions\":[{\"name\":\"jira\"}]}"));

        assertTrue(failure.getMessage().contains("hasOptions"), failure.getMessage());
    }

    @Test
    @DisplayName("contributions 取值必须是已知且类型级的扩展点")
    void parse_should_rejectContribution_when_typeIsUnknownOrRouted() {
        assertThrows(JellyfishException.class,
                () -> parse("{\"entry\":\"main.py\",\"contributions\":[\"telepathy\"]}"));
        // tool 是带路由键的扩展点，应在 tools 字段里声明
        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> parse("{\"entry\":\"main.py\",\"contributions\":[\"tool\"]}"));

        assertTrue(failure.getMessage().contains("类型级"), failure.getMessage());
    }

    @Test
    @DisplayName("contributions 与 events 重复声明应报错")
    void parse_should_rejectDuplicates_when_listsRepeatEntries() {
        assertThrows(JellyfishException.class,
                () -> parse("{\"entry\":\"main.py\",\"contributions\":[\"prompt\",\"prompt\"]}"));
        assertThrows(JellyfishException.class,
                () -> parse("{\"entry\":\"main.py\",\"events\":[\"E\",\"E\"]}"));
    }

    @Test
    @DisplayName("清单不是 JSON 对象应报错")
    void parse_should_rejectNonObject_when_rootIsNotObject() {
        assertThrows(JellyfishException.class, () -> parse("[]"));
        assertThrows(JellyfishException.class, () -> parse("{不是 JSON"));
    }

    @Test
    @DisplayName("可选字段缺省时应产出空集合而不是 null")
    void parse_should_produceEmptyCollections_when_optionalSectionsAreAbsent() {
        ScriptManifest manifest = parse("{\"entry\":\"main.py\"}");

        assertTrue(manifest.tools().isEmpty());
        assertTrue(manifest.commands().isEmpty());
        assertTrue(manifest.commandOptions().isEmpty());
        assertTrue(manifest.contributions().isEmpty());
        assertTrue(manifest.events().isEmpty());
    }

    /**
     * 解析清单正文。
     *
     * @param json 清单正文
     * @return 清单
     */
    private static ScriptManifest parse(String json) {
        return ScriptManifest.parse(json, FALLBACK_ID, ExtensionCodecs.DEFAULTS);
    }
}
