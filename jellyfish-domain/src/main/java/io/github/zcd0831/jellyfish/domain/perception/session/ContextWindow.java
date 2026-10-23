package io.github.zcd0831.jellyfish.domain.perception.session;

import java.util.List;

import lombok.Value;

/** 窗口推导产物，不落库。规则出处：模块-感知 §8.5。 */
@Value
public class ContextWindow {

    List<Record> records;
    ContextUsage usage;
}
