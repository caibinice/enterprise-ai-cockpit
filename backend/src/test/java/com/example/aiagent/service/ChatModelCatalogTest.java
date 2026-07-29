package com.example.aiagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.aiagent.config.LlmProperties;
import org.junit.jupiter.api.Test;

class ChatModelCatalogTest {
    private final ChatModelCatalog catalog = new ChatModelCatalog(
        new LlmProperties(
            true,
            "openai-compatible",
            "https://example.invalid/v1",
            "test-key",
            ChatModelCatalog.FLASH
        )
    );

    @Test
    void exposesOnlyApprovedDeepSeekModels() {
        assertThat(catalog.options())
            .extracting("id")
            .containsExactly(ChatModelCatalog.FLASH, ChatModelCatalog.PRO);
        assertThat(catalog.resolve(ChatModelCatalog.PRO))
            .isEqualTo(ChatModelCatalog.PRO);
    }

    @Test
    void rejectsArbitraryProviderModelIds() {
        assertThatThrownBy(() -> catalog.resolve("untrusted-model"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的模型");
    }
}
