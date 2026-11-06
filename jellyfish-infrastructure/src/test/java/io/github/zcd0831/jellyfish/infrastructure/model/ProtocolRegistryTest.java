package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.domain.action.model.ApiCredential;
import io.github.zcd0831.jellyfish.domain.action.model.ChatProtocol;
import io.github.zcd0831.jellyfish.domain.action.model.Provider;
import io.github.zcd0831.jellyfish.domain.action.model.ProviderStatus;
import io.github.zcd0831.jellyfish.domain.shared.ProviderId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 查表的双向锁：每种协议都必须有适配器，否则调这个协议的模型会静默降级成「协议未实现」。
 * 规则出处：05-action §5.4.2（协议差异用适配器承载）、09-infrastructure §9.11。
 */
class ProtocolRegistryTest {

    @Test
    void 每种协议都有适配器() {
        ProtocolRegistry registry = new ProtocolRegistry();
        for (ChatProtocol protocol : ChatProtocol.values()) {
            assertNotNull(registry.of(provider(protocol)),
                    "协议 " + protocol + " 已进枚举却没有适配器 ⇒ 调这个协议的模型永远 UNAVAILABLE");
        }
    }

    @Test
    void 没有供应商就是没有适配器() {
        assertNull(new ProtocolRegistry().of(null), "缺供应商要返回 null 让降级链继续试，不是抛错");
    }

    private static Provider provider(ChatProtocol protocol) {
        return Provider.builder(ProviderId.of("p-" + protocol.name()), "p", "http://localhost")
                .protocol(protocol).credential(new ApiCredential("K")).status(ProviderStatus.ACTIVE).build();
    }
}
