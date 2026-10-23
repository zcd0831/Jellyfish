package io.github.zcd0831.jellyfish.di;

import dagger.Component;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.InstanceLock;
import io.github.zcd0831.jellyfish.application.shared.port.SchemaMigrator;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;

import javax.inject.Singleton;

/**
 * 装配根。S0 只暴露已实现的端口；其余端口随切片（D15）加入。
 */
@Singleton
@Component(modules = InfrastructureModule.class)
public interface AppComponent {

    DataDirectory dataDirectory();

    JsonCodec jsonCodec();

    SessionRepository sessionRepository();

    RecordStore recordStore();

    CommitScope commitScope();

    InstanceLock instanceLock();

    SchemaMigrator schemaMigrator();
}
