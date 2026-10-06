package com.fragpicker.integration.chat;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatModelConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ChatModelConfiguration.class, Binding.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatModelProperties.class)
    static class Binding { }

    @Test
    void staysDisabledWithoutCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ChatModel.class)
                    .doesNotHaveBean(StreamingChatModel.class);
        });
    }

    @Test
    void createsBothModelsWithoutContactingProviderAtStartup() {
        runner.withPropertyValues("fragpicker.integrations.chat.enabled=true",
                "fragpicker.integrations.chat.base-url=https://api.deepseek.com",
                "fragpicker.integrations.chat.api-key=contract-test-key",
                "fragpicker.integrations.chat.model=contract-test-model",
                "fragpicker.integrations.chat.timeout=5s", "fragpicker.integrations.chat.max-output-tokens=1024")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ChatModel.class)
                        .hasSingleBean(StreamingChatModel.class));
    }

    @Test
    void refusesIncompleteEnabledConfiguration() {
        runner.withPropertyValues("fragpicker.integrations.chat.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsUnsafeUrlsAndUnboundedRequestsWithoutEchoingSecrets() {
        for (String url : new String[]{"http://example.com", "https://key@example.com/v1", "https://example.com?key=secret"}) {
            assertThatThrownBy(() -> properties(url, "key", Duration.ofSeconds(5), 1024).validateEnabled())
                    .hasMessageContaining("AI_CHAT_BASE_URL").hasMessageNotContaining("secret");
        }
        assertThatThrownBy(() -> properties("https://api.deepseek.com", "", Duration.ofSeconds(5), 1024).validateEnabled())
                .hasMessageContaining("AI_CHAT_API_KEY");
        assertThatThrownBy(() -> properties("https://api.deepseek.com", "key", Duration.ofHours(1), 1024).validateEnabled())
                .hasMessageContaining("AI_CHAT_TIMEOUT");
        assertThatThrownBy(() -> properties("https://api.deepseek.com", "key", Duration.ofSeconds(5), 0).validateEnabled())
                .hasMessageContaining("AI_CHAT_MAX_OUTPUT_TOKENS");
        assertThat(properties("https://api.deepseek.com", "private-test-key", Duration.ofSeconds(5), 1024).toString())
                .doesNotContain("private-test-key").contains("REDACTED");
    }

    private ChatModelProperties properties(String url, String key, Duration timeout, int maxTokens) {
        return new ChatModelProperties(true, url, key, "test-model", timeout, maxTokens);
    }
}
