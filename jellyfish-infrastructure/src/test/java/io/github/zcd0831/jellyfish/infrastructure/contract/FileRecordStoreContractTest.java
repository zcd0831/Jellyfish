package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileRecordStore;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;

class FileRecordStoreContractTest extends RecordStoreContract {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tmp;

    @Override
    protected RecordStore store() {
        return new FileRecordStore(DataDirectory.of(tmp.toString()), new JsonCodec());
    }

    @Override
    protected SessionRepository sessions() {
        return new FileSessionRepository(DataDirectory.of(tmp.toString()), new JsonCodec());
    }
}
