package io.github.zcd0831.jellyfish.domain.shared;

import lombok.Value;

/** 标识值对象。规则出处：模型图 §一（各聚合的标识）。 */
@Value
public class RecordId {

    String value;

    public static RecordId of(String value) {
        return new RecordId(value);
    }

    /** 日志与 JSON 里直接呈现为原始值，避免出现 SessionId(value=…)。 */
    @Override
    public String toString() {
        return value;
    }
}
