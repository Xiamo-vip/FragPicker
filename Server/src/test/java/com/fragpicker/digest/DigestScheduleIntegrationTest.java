package com.fragpicker.digest;

import com.fragpicker.auth.*;
import com.fragpicker.ingestion.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+")
class DigestScheduleIntegrationTest {
    private static final LocalDate DAY=LocalDate.of(2026,10,5);
    @Autowired DigestScheduleStore schedule;
    @Autowired DigestMapper jobs;
    @Autowired DigestStore worker;
    @Autowired RegistrationService registrations;
    @Autowired SubmissionService submissions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    private final List<Long> owners=new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",() -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",() -> System.getenv("DB_TEST_PASSWORD"));
    }
    @BeforeEach void before() { at("2026-10-05T13:59:00Z"); }
    @AfterEach void clean() { owners.forEach(owner -> jdbc.update("DELETE FROM users WHERE id=?",owner)); }
    private void at(String instant) { when(clock.instant()).thenReturn(Instant.parse(instant)); }
    private long owner() { long id=registrations.register(new RegisterRequest("schedule_"+UUID.randomUUID().toString().substring(0,8),"Integration-123!")).id(); owners.add(id); return id; }
    private SubmissionResponse submit(long owner,String key,String link) { return submissions.submit(owner,key,new SubmissionRequest(link,null)); }
    private SubmissionResponse submit(long owner) { return submit(owner,UUID.randomUUID().toString(),"https://b23.tv/"+UUID.randomUUID()); }
    private void change(SubmissionResponse fragment,long owner) { new TransactionTemplate(transactions).executeWithoutResult(tx -> { jobs.lockUser(owner); schedule.changedForFragment(owner,fragment.fragmentId()); }); }
    private DigestChange due(long owner) { return schedule.due().stream().filter(row -> row.userId()==owner).findFirst().orElseThrow(); }
    private void dispatch(long owner) { assertThat(schedule.dispatch(due(owner))).isTrue(); }
    private long version(long owner) { return jdbc.queryForObject("SELECT version FROM daily_digest_changes WHERE user_id=?",Long.class,owner); }
    @Test void cutoffAndLateCompletionWindowsAreIndependentOfServerTimezone() {
        assertThat(DigestScheduleStore.dueAt(DAY,Instant.parse("2026-10-05T14:00:00Z"))).isEqualTo(LocalDateTime.parse("2026-10-05T14:00:00"));
        assertThat(DigestScheduleStore.dueAt(DAY,Instant.parse("2026-10-05T14:00:01Z"))).isEqualTo(LocalDateTime.parse("2026-10-05T16:15:00"));
        assertThat(DigestScheduleStore.dueAt(DAY,Instant.parse("2026-10-07T01:00:00Z"))).isEqualTo(LocalDateTime.parse("2026-10-07T01:00:00"));
    }
    @Test void deduplicatedSubmissionsDoNotDirtyTheDayAndBefore22DoesNotDispatch() {
        long owner=owner(); var key=UUID.randomUUID().toString(); var link="https://b23.tv/"+UUID.randomUUID();
        submit(owner,key,link); submit(owner,key,link); submit(owner,UUID.randomUUID().toString(),link);
        assertThat(version(owner)).isEqualTo(1); assertThat(schedule.due()).isEmpty();
        at("2026-10-05T14:00:00Z"); var change=due(owner); assertThat(schedule.dispatch(change)).isTrue();
        assertThat(schedule.dispatch(change)).isFalse(); assertThat(jobs.day(owner,DAY).requestedRevision()).isEqualTo(1);
    }
    @Test void missedCutoffIsRecoveredAfterRestartAndFutureDaysStayQueuedInChanges() {
        long old=owner(); submit(old); at("2026-10-07T01:00:00Z");
        long today=owner(); submit(today); var restarted=new DigestScheduler(schedule); restarted.tick();
        assertThat(jobs.day(old,DAY).status()).isEqualTo("QUEUED");
        assertThat(jobs.day(today,LocalDate.of(2026,10,7))).isNull();
        restarted.tick(); assertThat(jobs.day(old,DAY).requestedRevision()).isEqualTo(1);
    }
    @Test void completedDayWaitsFor0015AndQueuedChangesAreCoalesced() {
        long owner=owner(); var item=submit(owner); at("2026-10-05T14:00:00Z"); dispatch(owner);
        var lease=worker.claim().orElseThrow(); worker.snapshot(lease);
        assertThat(worker.complete(lease,new DigestPiece(owner,DAY,0,0,"暂无已完成资料",List.of(),List.of(),List.of()))).isTrue();
        at("2026-10-05T15:00:00Z"); change(item,owner); assertThat(schedule.due()).isEmpty();
        at("2026-10-05T16:14:59Z"); assertThat(schedule.due()).isEmpty();
        at("2026-10-05T16:15:00Z"); dispatch(owner); assertThat(jobs.day(owner,DAY).requestedRevision()).isEqualTo(2);
        change(item,owner); dispatch(owner); assertThat(jobs.day(owner,DAY).requestedRevision()).isEqualTo(2);
        assertThat(jobs.day(owner,DAY).completedRevision()).isEqualTo(1);
    }
    @Test void runningDayQueuesOneNextRevisionAndFailuresNeverAutomaticallyRepeat() {
        long owner=owner(); var item=submit(owner); at("2026-10-05T14:00:00Z"); dispatch(owner); var lease=worker.claim().orElseThrow();
        at("2026-10-06T01:00:00Z"); change(item,owner); dispatch(owner); change(item,owner); dispatch(owner);
        assertThat(jobs.day(owner,DAY).requestedRevision()).isEqualTo(2); assertThat(jobs.day(owner,DAY).workingRevision()).isEqualTo(1);
        assertThat(worker.fail(lease,"DIGEST_AI_UNCONFIRMED")).isTrue(); change(item,owner); dispatch(owner);
        assertThat(jobs.day(owner,DAY).status()).isEqualTo("FAILED"); assertThat(jobs.day(owner,DAY).requestedRevision()).isEqualTo(2);
        assertThat(worker.claim()).isEmpty();
    }
    @Test void rollbackDiscardsChangeAndTwoDispatchersCommitOneRevision() throws Exception {
        long owner=owner(); var item=submit(owner);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { jobs.lockUser(owner); schedule.changedForFragment(owner,item.fragmentId()); tx.setRollbackOnly(); });
        assertThat(version(owner)).isEqualTo(1); at("2026-10-05T14:00:00Z"); var change=due(owner);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> schedule.dispatch(change)); var second=pool.submit(() -> schedule.dispatch(change));
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jobs.day(owner,DAY).requestedRevision()).isEqualTo(1);
    }
}
