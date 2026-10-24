package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileExperienceRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;

class FileExperienceRepositoryContractTest extends ExperienceRepositoryContract {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tmp;

    @Override
    protected ExperienceRepository repository() {
        return new FileExperienceRepository(DataDirectory.of(tmp.toString()), new JsonCodec());
    }
}
