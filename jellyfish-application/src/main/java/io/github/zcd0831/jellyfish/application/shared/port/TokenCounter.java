package io.github.zcd0831.jellyfish.application.shared.port;

import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import java.util.List;

/**
 * 事前计数，用于决定是否折叠；台账仍用厂商 usage。规则出处：技术决策 D08。
 */
public interface TokenCounter {

    int count(List<String> messages, ModelId modelId);
}
