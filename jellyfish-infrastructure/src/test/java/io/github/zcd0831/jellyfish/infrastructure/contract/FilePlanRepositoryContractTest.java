package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FilePlanRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;

class FilePlanRepositoryContractTest extends PlanRepositoryContract {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tmp;

    @Override
    protected PlanRepository repository() {
        return new FilePlanRepository(DataDirectory.of(tmp.toString()), new JsonCodec());
    }

    @Override
    protected SessionRepository sessions() {
        return new FileSessionRepository(DataDirectory.of(tmp.toString()), new JsonCodec());
    }
}
