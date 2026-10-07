package zcd.jellyfish.server.dto;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.SessionUsageSnapshot;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DTO 的 JSON 形状：请求体反序列化、响应体序列化、以及字段名就是 HTTP 合同这一点。
 * <p>
 * 这些断言是「前端不会因为内核改个字段名就崩」的唯一护栏，因此刻意断言<b>字面量</b>而不是
 * 「反序列化回来相等」——后者对字段名拼写错误毫无感知。
 *
 * @author zcd
 */
class JsonDtoTest {

    @Test
    void turnToolDoneEvent_should_exposeMetadataAsJsonObject() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("exitCode", Integer.valueOf(1));
        metadata.put("terminal", "COMPLETED");

        String json = ObjectMapperWrapper.writeValueAsString(new TurnToolDoneEvent("t1", "c1", "shell", true,
                "cwd: /x · exit: 1", metadata));

        // 字段名就是 HTTP 合同，因此断言字面量
        assertTrue(json.contains("\"metadata\":{\"exitCode\":1,\"terminal\":\"COMPLETED\"}"), json);
    }

    @Test
    void turnToolDoneEvent_should_exposeEmptyMetadata_when_absent() {
        String json = ObjectMapperWrapper.writeValueAsString(new TurnToolDoneEvent("t1", "c1", "read_file", true,
                "内容", null));

        assertTrue(json.contains("\"metadata\":{}"), json);
    }

    @Test
    void createSessionRequest_should_bind_all_fields_when_json_given() {
        CreateSessionRequest request = ObjectMapperWrapper.readValue(
                "{\"agentId\":\"coder\",\"provider\":\"openai\",\"model\":\"gpt-4o\"}",
                CreateSessionRequest.class);

        assertEquals("coder", request.getAgentId());
        assertEquals("openai", request.getProvider());
        assertEquals("gpt-4o", request.getModel());
    }

    @Test
    void createSessionRequest_should_leave_fields_null_when_json_empty_object() {
        CreateSessionRequest request = ObjectMapperWrapper.readValue("{}", CreateSessionRequest.class);

        assertNull(request.getAgentId());
        assertNull(request.getProvider());
        assertNull(request.getModel());
    }

    @Test
    void chatRequest_should_bind_message_when_json_given() {
        ChatRequest request = ObjectMapperWrapper.readValue("{\"message\":\"你好\"}", ChatRequest.class);

        assertEquals("你好", request.getMessage());
    }

    @Test
    void approvalDecisionRequest_should_distinguish_missing_and_false() {
        ApprovalDecisionRequest missing = ObjectMapperWrapper.readValue("{}", ApprovalDecisionRequest.class);
        ApprovalDecisionRequest denied = ObjectMapperWrapper.readValue("{\"approved\":false}",
                ApprovalDecisionRequest.class);

        assertNull(missing.getApproved());
        assertFalse(denied.getApproved());
    }

    @Test
    void commandExecRequest_should_bind_structured_form_when_args_given() {
        CommandExecRequest request = ObjectMapperWrapper.readValue(
                "{\"name\":\"model\",\"args\":[\"openai/gpt-4o\"]}", CommandExecRequest.class);

        assertEquals("model", request.getName());
        assertEquals(Collections.singletonList("openai/gpt-4o"), request.getArgs());
        assertNull(request.getInput());
    }

    @Test
    void sessionSummary_should_serialize_expected_field_names_when_serialized() {
        SessionSummary summary = new SessionSummary("s1", "标题", "coder", "openai", "gpt-4o",
                1000L, 2000L, 3, new SessionUsageSnapshot(1L, 2L, 3L, 4L, 5L, 6L));

        String json = ObjectMapperWrapper.writeValueAsString(summary);

        assertTrue(json.contains("\"sessionId\":\"s1\""), json);
        assertTrue(json.contains("\"messageCount\":3"), json);
        assertTrue(json.contains("\"totalTokens\":3"), json);
    }

    @Test
    void commandResultDto_should_serialize_kind_and_choices_when_projected() {
        CommandResultDto dto = new CommandResultDto(CommandResultDto.KIND_OK, "可用模型：",
                Collections.singletonList(new ChoiceDto("openai/gpt-4o", "gpt-4o", null, true)), null);

        String json = ObjectMapperWrapper.writeValueAsString(dto);

        assertTrue(json.contains("\"kind\":\"OK\""), json);
        assertTrue(json.contains("\"value\":\"openai/gpt-4o\""), json);
        assertTrue(json.contains("\"selected\":true"), json);
    }

    @Test
    void commandResultDto_should_carry_handoff_when_command_requests_it() {
        // When
        CommandResultDto dto = CommandResultDto.of(CommandResult.handoff("请阅读当前仓库并写出 AGENTS.md"));

        // Then：接力文本要能被客户端取到，且此时没有可渲染文本、没有候选
        assertEquals("请阅读当前仓库并写出 AGENTS.md", dto.getHandoff());
        assertNull(dto.getOutput());
        assertTrue(dto.getChoices().isEmpty());
    }

    @Test
    void approvalDto_should_project_pending_fields_when_created() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("path", "/tmp/x");
        ApprovalChannel.Pending pending = new ApprovalChannel.Pending("s1", "coder", "write_file",
                arguments, "写文件需要确认");

        ApprovalDto dto = ApprovalDto.of(pending);

        assertEquals(pending.getId(), dto.getRequestId());
        assertEquals("s1", dto.getSessionId());
        assertEquals("write_file", dto.getToolName());
        assertEquals("/tmp/x", dto.getArguments().get("path"));
        assertEquals("写文件需要确认", dto.getReason());
    }

    @Test
    void approvalDto_should_serialize_arguments_as_object_when_serialized() {
        ApprovalChannel.Pending pending = new ApprovalChannel.Pending("s1", null, "read_file",
                Collections.singletonMap("path", "/a"), null);

        String json = ObjectMapperWrapper.writeValueAsString(ApprovalDto.of(pending));

        assertTrue(json.contains("\"requestId\":\"" + pending.getId() + "\""), json);
        assertTrue(json.contains("\"arguments\":{\"path\":\"/a\"}"), json);
    }
}
