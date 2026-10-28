package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.action.model.ApiCredential;
import io.github.zcd0831.jellyfish.domain.action.model.ChatProtocol;
import io.github.zcd0831.jellyfish.domain.action.model.Provider;
import io.github.zcd0831.jellyfish.domain.action.model.ProviderStatus;
import io.github.zcd0831.jellyfish.domain.shared.ProviderId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * providers/<id>.json：端点、协议、凭据引用。由用户自建，不随 jar 播种。
 */
final class ProviderCatalog {

    private final DataDirectory dataDirectory;
    private final JsonCodec codec;

    ProviderCatalog(DataDirectory dataDirectory, JsonCodec codec) {
        this.dataDirectory = dataDirectory;
        this.codec = codec;
    }

    List<Provider> all() {
        List<Provider> out = new ArrayList<Provider>();
        for (Path file : jsonFiles()) {
            out.add(codec.read(read(file), ProviderDto.class).toDomain());
        }
        return out;
    }

    void save(Provider provider) {
        AtomicFiles.write(dataDirectory.providers().resolve(provider.id().getValue() + ".json"),
                codec.write(ProviderDto.from(provider)));
    }

    private List<Path> jsonFiles() {
        List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(dataDirectory.providers())) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDirectory.providers(), "*.json")) {
            for (Path path : stream) {
                out.add(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描供应商目录失败", e);
        }
        return out;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取供应商定义失败：" + file, e);
        }
    }

    public static class ProviderDto {

        public String id;
        public String name;
        public String apiEndpoint;
        public String protocol;
        public CredentialDto credential;
        public String status;

        static ProviderDto from(Provider provider) {
            ProviderDto dto = new ProviderDto();
            dto.id = provider.id().getValue();
            dto.name = provider.name();
            dto.apiEndpoint = provider.apiEndpoint();
            dto.protocol = provider.protocol().name();
            dto.status = provider.status().name();
            if (provider.credential() != null) {
                dto.credential = new CredentialDto();
                dto.credential.apiKeyRef = provider.credential().getApiKeyRef();
            }
            return dto;
        }

        Provider toDomain() {
            return new Provider(ProviderId.of(id), name, apiEndpoint,
                    protocol == null ? ChatProtocol.OPENAI_CHAT : ChatProtocol.valueOf(protocol),
                    credential == null ? null : new ApiCredential(credential.apiKeyRef),
                    ProviderStatus.valueOf(status));
        }
    }

    public static class CredentialDto {
        public String apiKeyRef;
    }
}
