package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;

/** 文件实现跑同一套契约。 */
class FileSessionRepositoryContractTest extends SessionRepositoryContract {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tmp;

    @Override
    protected SessionRepository repository() {
        return new FileSessionRepository(DataDirectory.of(tmp.toString()), new JsonCodec());
    }
}
