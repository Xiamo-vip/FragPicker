package com.fragpicker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SchedulerIsolationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(ScheduledTasks.class);

    @Test void productionDefaultsKeepOtherJobsRunningWhileSevenWorkersAreBlocked() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed(); var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(8);
            var entered = new CountDownLatch(7); var release = new CountDownLatch(1); var completed = new CountDownLatch(1);
            try {
                for (int i = 0; i < 7; i++) scheduler.schedule(() -> { entered.countDown(); await(release); }, Instant.now());
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                scheduler.schedule(() -> { assertThat(Thread.currentThread().getName()).startsWith("fragpicker-scheduled-"); completed.countDown(); }, Instant.now());
                assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            } finally { release.countDown(); }
        });
    }
    @Test void explicitPoolSettingIsBoundAndSameFixedDelayTaskDoesNotOverlap() {
        contexts.withPropertyValues("SCHEDULER_POOL_SIZE=2").run(context -> {
            assertThat(context).hasNotFailed(); var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var other = new CountDownLatch(1);
            var active = new AtomicInteger(); var maximum = new AtomicInteger(); var invocations = new AtomicInteger();
            var task = scheduler.scheduleWithFixedDelay(() -> { maximum.accumulateAndGet(active.incrementAndGet(), Math::max); invocations.incrementAndGet(); entered.countDown(); await(release); active.decrementAndGet(); }, Duration.ofMillis(10));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue(); scheduler.schedule(other::countDown, Instant.now()); assertThat(other.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(invocations.get()).isEqualTo(1); assertThat(maximum.get()).isEqualTo(1);
            } finally { task.cancel(true); release.countDown(); }
        });
    }
    @Test void rejectsNonPositivePoolRatherThanSilentlyUsingSingleThreadFallback() {
        contexts.withPropertyValues("SCHEDULER_POOL_SIZE=0").run(context -> assertThat(context).hasFailed());
    }
    private static void await(CountDownLatch latch) { try { latch.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } }
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class ScheduledTasks { }
}
