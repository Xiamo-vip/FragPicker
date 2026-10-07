package com.fragpicker.digest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DigestScheduleConfigurationTest {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(DigestScheduleProperties.class)
    static class Properties { }
    @Test void enabledConfigurationActuallyRunsRecurringScheduling() {
        var store=mock(DigestScheduleStore.class); var ticks=new CountDownLatch(2);
        when(store.due()).thenAnswer(call -> { ticks.countDown(); return List.of(); });
        new ApplicationContextRunner().withInitializer(context -> { context.getEnvironment().setActiveProfiles("database"); context.getBeanFactory().registerSingleton("testStore",store); })
            .withUserConfiguration(Properties.class,DigestScheduleConfiguration.class)
            .withPropertyValues("fragpicker.digest.schedule.enabled=true","fragpicker.digest.schedule.poll-delay=250ms")
            .run(context -> { assertThat(context).hasSingleBean(DigestScheduler.class); assertThat(ticks.await(5,TimeUnit.SECONDS)).isTrue(); });
    }
    @Test void disabledDoesNotScheduleAndInvalidEnabledIntervalFailsStartup() {
        var runner=new ApplicationContextRunner().withInitializer(context -> { context.getEnvironment().setActiveProfiles("database"); context.getBeanFactory().registerSingleton("testStore",mock(DigestScheduleStore.class)); })
            .withUserConfiguration(Properties.class,DigestScheduleConfiguration.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(DigestScheduler.class));
        runner.withPropertyValues("fragpicker.digest.schedule.enabled=true","fragpicker.digest.schedule.poll-delay=0s")
            .run(context -> { assertThat(context).hasFailed(); assertThat(context.getStartupFailure()).hasRootCauseMessage("Invalid DIGEST_SCHEDULE_POLL_DELAY"); });
    }
}
