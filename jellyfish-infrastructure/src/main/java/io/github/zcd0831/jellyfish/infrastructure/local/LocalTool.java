package io.github.zcd0831.jellyfish.infrastructure.local;

import io.github.zcd0831.jellyfish.application.shared.CancellationToken;

import java.util.Map;

/** 一个本地能力：入参是已解析的 JSON 对象，返回回灌给模型的文本。 */
interface LocalTool {

    String run(Map<String, Object> args) throws Exception;

    /** 默认不关心终止；会阻塞的工具（run_command）覆写它。 */
    default String run(Map<String, Object> args, CancellationToken token) throws Exception {
        return run(args);
    }
}
