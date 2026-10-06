package com.fragpicker.knowledge.index;

import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndexConfigurationTest {
    @Test void schedulesOnlyWhenExplicitlyEnabled() {
        var properties = defaults();
        var runner = new ApplicationContextRunner().withUserConfiguration(IndexConfiguration.class)
                .withPropertyValues("spring.profiles.active=database", "fragpicker.knowledge.index.poll-delay=1m")
                .withBean(IndexStore.class, () -> mock(IndexStore.class)).withBean(LocalEmbeddingService.class, () -> mock(LocalEmbeddingService.class))
                .withBean(IndexProperties.class, () -> properties);
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(IndexWorker.class));
        runner.withPropertyValues("fragpicker.knowledge.index.enabled=true").run(ctx -> assertThat(ctx).hasSingleBean(IndexWorker.class));
    }
    @Test void validatesAdjustableResourceLimitsAndBackoff() {
        defaults().validate(); assertThat(defaults().retryDelay(10)).isEqualTo(3600);
        assertThatThrownBy(() -> new IndexProperties(true, Duration.ZERO, Duration.ofMinutes(10), 3, Duration.ofSeconds(10), 10000).validate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IndexProperties(true, Duration.ofSeconds(2), Duration.ofHours(2), 3, Duration.ofSeconds(10), 10000).validate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IndexProperties(true, Duration.ofSeconds(2), Duration.ofMinutes(10), 0, Duration.ofSeconds(10), 10000).validate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IndexProperties(true, Duration.ofSeconds(2), Duration.ofMinutes(10), 3, Duration.ofSeconds(10), 50001).validate()).isInstanceOf(IllegalStateException.class);
    }
    private IndexProperties defaults() { return new IndexProperties(false, Duration.ofSeconds(2), Duration.ofMinutes(10), 3, Duration.ofSeconds(10), 10000); }
}
