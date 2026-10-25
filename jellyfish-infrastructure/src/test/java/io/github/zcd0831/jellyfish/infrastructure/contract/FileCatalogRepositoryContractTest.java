package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCatalogRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;

class FileCatalogRepositoryContractTest extends CatalogRepositoryContract {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tmp;

    @Override
    protected CatalogRepository repository() {
        return FileCatalogRepository.standalone(DataDirectory.of(tmp.toString()), new JsonCodec());
    }
}
