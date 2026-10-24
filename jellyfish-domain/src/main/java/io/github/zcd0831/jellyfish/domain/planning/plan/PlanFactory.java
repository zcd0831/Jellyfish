package io.github.zcd0831.jellyfish.domain.planning.plan;

import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 把 `generate_plan` 的草案变成合法计划；校验失败即工具失败（回灌模型，不消耗重试）。
 * 规则出处：模块-规划 §7.1 规则 1、§8.4。
 */
public final class PlanFactory {

    /** 「规模不荒谬」的硬上限：模型偶尔会吐出几十步的伪计划。 */
    private static final int MAX_STEPS = 30;

    public Plan create(PlanId id, SessionId sessionId, Map<String, Object> args, Set<ToolId> boundToolIds) {
        List<Map<String, Object>> rawSteps = stepsOf(args);
        if (rawSteps.isEmpty()) {
            throw invalid("计划至少要有一个步骤");
        }
        if (rawSteps.size() > MAX_STEPS) {
            throw invalid("计划步骤过多（" + rawSteps.size() + " > " + MAX_STEPS + "）");
        }
        List<PlanStep> steps = new ArrayList<PlanStep>();
        for (int i = 0; i < rawSteps.size(); i++) {
            Map<String, Object> raw = rawSteps.get(i);
            Set<VerificationType> verifications = verificationsOf(raw, i);
            steps.add(PlanSteps.draft(StepId.of(UUID.randomUUID().toString()), i + 1,
                    descriptionOf(raw, i), textOf(raw, "goal", i), textOf(raw, "expectedOutcome", i),
                    textOf(raw, "verificationCriteria", i), verifications));
            if (verifications.contains(VerificationType.LLM_JUDGE)) {
                requireJudgeInputs(raw, i);
            }
            requireBoundTools(raw, i, boundToolIds);
        }
        requireAcyclic(rawSteps);
        return Plan.create(id, sessionId, goalOf(args), steps);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stepsOf(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("steps");
        if (!(raw instanceof List)) {
            throw invalid("缺少 steps 数组");
        }
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) {
                throw invalid("steps 里的每一项都必须是对象");
            }
            steps.add((Map<String, Object>) item);
        }
        return steps;
    }

    private static String goalOf(Map<String, Object> args) {
        Object goal = args == null ? null : args.get("goal");
        return goal == null ? null : String.valueOf(goal);
    }

    private static String descriptionOf(Map<String, Object> raw, int index) {
        Object description = raw.get("description");
        if (description == null || String.valueOf(description).trim().isEmpty()) {
            throw invalid("第 " + (index + 1) + " 步缺少 description");
        }
        return String.valueOf(description);
    }

    private static String textOf(Map<String, Object> raw, String key, int index) {
        Object value = raw.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 裁判要吃三要素：声明 LLM_JUDGE 就必须给全（M16）。 */
    private static void requireJudgeInputs(Map<String, Object> raw, int index) {
        requireText(raw, "goal", index, "LLM_JUDGE");
        requireText(raw, "expectedOutcome", index, "LLM_JUDGE");
        requireText(raw, "verificationCriteria", index, "LLM_JUDGE");
    }

    private static void requireText(Map<String, Object> raw, String key, int index, String why) {
        Object value = raw.get(key);
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            throw invalid("第 " + (index + 1) + " 步声明了 " + why + "，必须给出 " + key);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<VerificationType> verificationsOf(Map<String, Object> raw, int index) {
        Object declared = raw.get("verifications");
        Set<VerificationType> types = EnumSet.noneOf(VerificationType.class);
        if (declared == null) {
            return types;
        }
        if (!(declared instanceof List)) {
            throw invalid("第 " + (index + 1) + " 步的 verifications 必须是数组");
        }
        for (Object item : (List<Object>) declared) {
            try {
                types.add(VerificationType.valueOf(String.valueOf(item)));
            } catch (IllegalArgumentException e) {
                throw invalid("第 " + (index + 1) + " 步声明了未知的验证类型：" + item);
            }
        }
        return types;
    }

    @SuppressWarnings("unchecked")
    private static void requireBoundTools(Map<String, Object> raw, int index, Set<ToolId> boundToolIds) {
        Object declared = raw.get("tools");
        if (declared == null) {
            return;
        }
        if (!(declared instanceof List)) {
            throw invalid("第 " + (index + 1) + " 步的 tools 必须是数组");
        }
        for (Object item : (List<Object>) declared) {
            if (boundToolIds == null || !boundToolIds.contains(ToolId.of(String.valueOf(item)))) {
                throw invalid("第 " + (index + 1) + " 步引用了未绑定的工具：" + item);
            }
        }
    }

    /** 步骤依赖必须无环（§7.1 规则 1）。dependsOn 用 1 基序号。 */
    @SuppressWarnings("unchecked")
    private static void requireAcyclic(List<Map<String, Object>> rawSteps) {
        int size = rawSteps.size();
        List<List<Integer>> edges = new ArrayList<List<Integer>>();
        for (int i = 0; i < size; i++) {
            edges.add(new ArrayList<Integer>());
            Object dependsOn = rawSteps.get(i).get("dependsOn");
            if (dependsOn == null) {
                continue;
            }
            if (!(dependsOn instanceof List)) {
                throw invalid("第 " + (i + 1) + " 步的 dependsOn 必须是数组");
            }
            for (Object item : (List<Object>) dependsOn) {
                int target = toIndex(item, i);
                edges.get(i).add(target);
            }
        }
        boolean[] visiting = new boolean[size];
        boolean[] visited = new boolean[size];
        for (int i = 0; i < size; i++) {
            if (hasCycle(i, edges, visiting, visited)) {
                throw invalid("步骤依赖存在环");
            }
        }
    }

    private static int toIndex(Object raw, int own) {
        int value;
        try {
            value = Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw invalid("第 " + (own + 1) + " 步的 dependsOn 含非数字：" + raw);
        }
        if (value < 1 || value > own) {
            // 只能依赖前面的步骤：这样既无环，也不允许前向引用
            throw invalid("第 " + (own + 1) + " 步依赖了非法序号：" + value);
        }
        return value - 1;
    }

    private static boolean hasCycle(int node, List<List<Integer>> edges, boolean[] visiting, boolean[] visited) {
        if (visited[node]) {
            return false;
        }
        if (visiting[node]) {
            return true;
        }
        visiting[node] = true;
        for (int next : edges.get(node)) {
            if (hasCycle(next, edges, visiting, visited)) {
                return true;
            }
        }
        visiting[node] = false;
        visited[node] = true;
        return false;
    }

    private static PlanCreationException invalid(String detail) {
        return new PlanCreationException(PlanningError.INVALID_PLAN, detail);
    }
}
