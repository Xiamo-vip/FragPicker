package com.fragpicker.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.chat.ChatModelProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnrichmentConfigurationTest {
    private EnrichmentProperties worker(Duration lease) { return new EnrichmentProperties(true, Duration.ofSeconds(30), lease, 3, Duration.ofSeconds(10)); }
    private ChatModelProperties chat(boolean enabled) { return new ChatModelProperties(enabled, "https://api.deepseek.com", "test-only-key", "test-model", Duration.ofSeconds(60), 4096); }
    @Test void onlySchedulesWhenEnabled() {
        for (boolean enabled : new boolean[]{true, false}) {
            new ApplicationContextRunner().withUserConfiguration(EnrichmentConfiguration.class)
                    .withPropertyValues("spring.profiles.active=database", "fragpicker.knowledge.enrichment.enabled=" + enabled, "fragpicker.knowledge.enrichment.poll-delay=30s")
                    .withBean(EnrichmentStore.class, () -> mock(EnrichmentStore.class)).withBean(ChatModel.class, () -> mock(ChatModel.class))
                    .withBean(ObjectMapper.class, ObjectMapper::new).withBean(EnrichmentProperties.class, () -> worker(Duration.ofMinutes(5)))
                    .withBean(ChatModelProperties.class, () -> chat(true))
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        if (enabled) { assertThat(ctx).hasSingleBean(EnrichmentWorker.class); assertThat(ctx.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1); }
                        else assertThat(ctx).doesNotHaveBean(EnrichmentWorker.class);
                    });
        }
    }
    @Test void validatesWorkerBoundsDependenciesAndTimeoutBudget() {
        assertThatThrownBy(() -> new EnrichmentProperties(true, Duration.ZERO, Duration.ofMinutes(5), 3, Duration.ofSeconds(10)).validate()).hasMessageContaining("POLL_DELAY");
        var config = new EnrichmentConfiguration();
        assertThatThrownBy(() -> config.enrichmentWorker(mock(EnrichmentStore.class), mock(ChatModel.class), new ObjectMapper(), worker(Duration.ofMinutes(5)), chat(false))).hasMessageContaining("requires");
        assertThatThrownBy(() -> config.enrichmentWorker(mock(EnrichmentStore.class), mock(ChatModel.class), new ObjectMapper(), worker(Duration.ofMinutes(1)), chat(true))).hasMessageContaining("LEASE_DURATION");
    }
}
