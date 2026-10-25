package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;

import java.util.Map;

/**
 * 工具执行的扩展点：插件换掉循环后，仍可复用"校验 → 审批 → 分发 → 截断归档"这道闸门。
 * 规则出处：模块-行动 §8.1、D24-5。
 */
public interface ToolInvoker {

    ToolExecOutcome execute(Session session, String toolName, Map<String, Object> args, RecordId requestId);
}
