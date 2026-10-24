package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileBudgetRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;

class FileBudgetRepositoryContractTest extends BudgetRepositoryContract {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tmp;

    @Override
    protected BudgetRepository repository() {
        return new FileBudgetRepository(DataDirectory.of(tmp.toString()), new JsonCodec());
    }

    @Override
    protected SessionRepository sessions() {
        return new FileSessionRepository(DataDirectory.of(tmp.toString()), new JsonCodec());
    }
}
