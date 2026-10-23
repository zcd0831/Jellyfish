package io.github.zcd0831.jellyfish.domain.planning.plan;

import lombok.Value;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;

@Value
public class RecordRange {

    RecordId startRecordId;
    RecordId endRecordId;
}
