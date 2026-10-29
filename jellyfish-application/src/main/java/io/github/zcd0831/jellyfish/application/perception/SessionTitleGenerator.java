package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;

import java.util.Collections;

/**
 * 首条输入 → 标题；失败或超长降级为首句截断，不让建会话失败。
 * 规则出处：04-perception §4.9.4、§4.8.1 规则 3。
 */
public final class SessionTitleGenerator {

    private static final String INSTRUCTION = "用不超过 20 个字概括用户这段话的主题，只输出标题本身，不要标点结尾。";
    private static final int MAX_TITLE_LENGTH = 30;
    private static final int FALLBACK_LENGTH = 20;

    private final ModelInvocationPort model;
    private final BudgetService budgetService;

    public SessionTitleGenerator(ModelInvocationPort model, BudgetService budgetService) {
        this.model = model;
        this.budgetService = budgetService;
    }

    public String generate(Session session, String firstInput) {
        try {
            ModelInvocationResult result = model.invoke(new ModelInvocationRequest(session.id(), session.activeModelId(),
                    INSTRUCTION, Collections.singletonList(ChatMessage.of(RecordRole.USER, firstInput)),
                    Collections.emptyList(), 0, null));
            if (result.usage() != null) {
                budgetService.recordUsage(session, result.usage());
            }
            String title = result.text() == null ? "" : result.text().trim();
            if (!title.isEmpty() && title.length() <= MAX_TITLE_LENGTH) {
                return title;
            }
        } catch (RuntimeException ignored) {
            // 标题只是展示信息，失败降级即可（04-perception §4.9.4）
        }
        return fallback(firstInput);
    }

    private static String fallback(String firstInput) {
        if (firstInput == null) {
            return "未命名会话";
        }
        String singleLine = firstInput.replace('\n', ' ').trim();
        return singleLine.length() <= FALLBACK_LENGTH ? singleLine : singleLine.substring(0, FALLBACK_LENGTH);
    }
}
