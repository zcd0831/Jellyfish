package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.model.Provider;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.memory.skill.InstructionTemplate;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillStatus;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 技能写入：CREATE / MERGE / 容量 / 不可变守护（07-memory §7.9.5）。 */
class SkillDistillationServiceTest {

    private final FakeCatalog catalog = new FakeCatalog();
    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final CommitScope commitScope = mock(CommitScope.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final ProgressPort progress = mock(ProgressPort.class);

    private SkillDistillationService service(RuntimePolicy policy) {
        when(policies.load()).thenReturn(policy);
        // 全局写段在测试里直接执行 work，等价于串行段的语义
        org.mockito.Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(commitScope).globalWrite(any(Runnable.class));
        return new SkillDistillationService(catalog, policies, commitScope, events, progress);
    }

    @Test
    void 四件套不全时不写入() {
        service(RuntimePolicy.defaults()).apply(new SkillDraft(SkillDraft.Action.CREATE, null, "名",
                null, "触发", "正文"), SessionId.of("s1"));

        assertEquals(0, catalog.saved.size());
    }

    @Test
    void 新建时id带learned前缀() {
        service(RuntimePolicy.defaults()).apply(create("依赖升级"), SessionId.of("s1"));

        assertEquals(1, catalog.saved.size());
        assertTrue(catalog.saved.get(0).id().getValue().startsWith("learned."));
        assertTrue(catalog.saved.get(0).isMutable());
    }

    @Test
    void 撞容量上限时不写入并提示() {
        RuntimePolicy tiny = RuntimePolicy.defaults()
                .adjust(Collections.<String, Object>singletonMap("skillCapacity", 1));
        catalog.existing.add(skill("learned.x", true));

        service(tiny).apply(create("新技能"), SessionId.of("s1"));

        assertEquals(0, catalog.saved.size());
        verify(progress).emit(any());
    }

    @Test
    void MERGE到不可变技能时降级新建() {
        catalog.existing.add(skill("builtin.locked", false));

        service(RuntimePolicy.defaults()).apply(merge("builtin.locked", "新正文"), SessionId.of("s1"));

        assertEquals(1, catalog.saved.size(), "不可变技能不许改，改走新建");
        assertFalse(SkillId.of("builtin.locked").equals(catalog.saved.get(0).id()));
    }

    @Test
    void MERGE目标不存在时整次作废() {
        service(RuntimePolicy.defaults()).apply(merge("learned.ghost", "新正文"), SessionId.of("s1"));

        assertEquals(0, catalog.saved.size(), "幻觉 id 不奖励新建");
    }

    @Test
    void MERGE到可写技能时只改正文且保留id() {
        SkillDefinition existing = skill("learned.a", true);
        catalog.existing.add(existing);

        service(RuntimePolicy.defaults()).apply(merge("learned.a", "新正文"), SessionId.of("s1"));

        assertEquals(1, catalog.saved.size());
        assertEquals(SkillId.of("learned.a"), catalog.saved.get(0).id());
        assertEquals("新正文", catalog.saved.get(0).sopTemplate().getRawMarkdown());
        assertEquals("名", catalog.saved.get(0).name(), "MERGE 不动发现入口");
    }

    @Test
    void 新建与改进各发一个事件() {
        service(RuntimePolicy.defaults()).apply(create("甲"), SessionId.of("s1"));
        catalog.existing.add(skill("learned.b", true));
        service(RuntimePolicy.defaults()).apply(merge("learned.b", "改"), SessionId.of("s1"));

        verify(events, times(2)).publishAfterCommit(any());
    }

    private static SkillDraft merge(String targetSkillId, String sopBody) {
        return new SkillDraft(SkillDraft.Action.MERGE, SkillId.of(targetSkillId), "名", "述", "触发", sopBody);
    }

    private static SkillDraft create(String name) {
        return new SkillDraft(SkillDraft.Action.CREATE, null, name, "描述", "触发条件", "## 步骤");
    }

    private static SkillDefinition skill(String id, boolean mutable) {
        return new SkillDefinition(SkillId.of(id), "名", "述", "触发", new InstructionTemplate("正文"),
                SkillStatus.ACTIVE, mutable);
    }

    /** 只实现技能部分：这是被测服务唯一用到的目录能力。 */
    private static final class FakeCatalog implements CatalogRepository {

        private final List<SkillDefinition> existing = new ArrayList<SkillDefinition>();
        private final List<SkillDefinition> saved = new ArrayList<SkillDefinition>();

        @Override
        public List<SkillDefinition> skills() {
            List<SkillDefinition> all = new ArrayList<SkillDefinition>(existing);
            all.addAll(saved);
            return all;
        }

        @Override
        public int countActiveSkills() {
            int active = 0;
            for (SkillDefinition skill : skills()) {
                if (skill.isActive()) {
                    active++;
                }
            }
            return active;
        }

        @Override
        public void saveSkill(SkillDefinition skill) {
            saved.add(skill);
        }

        @Override
        public List<ToolDefinition> tools() {
            return Collections.emptyList();
        }

        @Override
        public List<AgentDefinition> agents() {
            return Collections.emptyList();
        }

        @Override
        public List<Provider> providers() {
            return Collections.emptyList();
        }

        @Override
        public List<Model> models() {
            return Collections.emptyList();
        }

        @Override
        public void saveTool(ToolDefinition tool) {
        }

        @Override
        public void saveAgent(AgentDefinition agent) {
        }

        @Override
        public void saveProvider(Provider provider) {
        }

        @Override
        public void saveModel(Model model) {
        }
    }
}
