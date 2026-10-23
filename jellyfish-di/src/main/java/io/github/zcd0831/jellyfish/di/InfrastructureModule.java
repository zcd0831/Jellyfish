package io.github.zcd0831.jellyfish.di;

import dagger.Module;
import dagger.Provides;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.InstanceLock;
import io.github.zcd0831.jellyfish.application.shared.port.SchemaMigrator;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCommitScope;
import io.github.zcd0831.jellyfish.infrastructure.file.FileInstanceLock;
import io.github.zcd0831.jellyfish.infrastructure.file.FileRecordStore;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSchemaMigrator;
import io.github.zcd0831.jellyfish.infrastructure.file.FileSessionRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;

import javax.inject.Singleton;

/**
 * 端口 → 适配器。DI 注解只允许出现在本模块（技术决策 D04）。
 */
@Module
public final class InfrastructureModule {

    private final DataDirectory dataDirectory;

    public InfrastructureModule(DataDirectory dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    @Provides
    @Singleton
    DataDirectory dataDirectory() {
        return dataDirectory;
    }

    @Provides
    @Singleton
    JsonCodec jsonCodec() {
        return new JsonCodec();
    }

    @Provides
    @Singleton
    SessionRepository sessionRepository(DataDirectory dir, JsonCodec codec) {
        return new FileSessionRepository(dir, codec);
    }

    @Provides
    @Singleton
    RecordStore recordStore(DataDirectory dir, JsonCodec codec) {
        return new FileRecordStore(dir, codec);
    }

    @Provides
    @Singleton
    InstanceLock instanceLock(DataDirectory dir) {
        return new FileInstanceLock(dir);
    }

    @Provides
    @Singleton
    SchemaMigrator schemaMigrator(DataDirectory dir) {
        return new FileSchemaMigrator(dir);
    }

    /** 每次注入一个新的写入作用域：一个事务一个实例。 */
    @Provides
    CommitScope commitScope(DataDirectory dir, JsonCodec codec) {
        return new FileCommitScope(dir, codec);
    }
}
