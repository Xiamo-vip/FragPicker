package com.fragpicker.digest;

import com.fragpicker.auth.*;
import com.fragpicker.ingestion.*;
import com.fragpicker.knowledge.EnrichmentResult.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+")
@Transactional
class DigestStoreIntegrationTest {
    private static final LocalDate DAY=LocalDate.of(2026,10,6);
    private static final LocalDateTime DUE=LocalDateTime.of(2026,1,1,0,0);
    @Autowired DigestStore store;
    @Autowired DigestMapper jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registrations;
    @Autowired SubmissionService submissions;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",() -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",() -> System.getenv("DB_TEST_PASSWORD"));
    }
    private long owner() { return registrations.register(new RegisterRequest("digest_"+UUID.randomUUID().toString().substring(0,8),"Integration-123!")).id(); }
    private long source(long owner, LocalDate date, boolean ready) {
        long id=submissions.submit(owner,UUID.randomUUID().toString(),new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(),null)).fragmentId();
        jdbc.update("UPDATE fragments SET business_date=?,status=? WHERE id=?",date,ready?"READY":"PENDING",id);
        jdbc.update("INSERT INTO fragment_knowledge(fragment_id,user_id,duration_ms,summary,keywords,completed_at,enriched_summary,bullet_points,categories,enriched_at,enrichment_model) VALUES (?,?,3000,'原摘要',JSON_ARRAY('导数'),UTC_TIMESTAMP(3),'学习导数与变化率',JSON_ARRAY('导数衡量瞬时变化率'),JSON_ARRAY('LEARNING'),UTC_TIMESTAMP(3),'test')",id,owner);
        return id;
    }
    private DigestLease lease(long owner) { store.enqueue(owner,DAY,DUE,false); return store.claim().orElseThrow(); }
    private void expire(DigestLease lease) { jdbc.update("UPDATE daily_digests SET lease_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",lease.id()); }
    private DigestPiece piece(long owner,long count,long id) { return new DigestPiece(owner,DAY,count,1,"学习导数",List.of(new DigestPoint("导数表示瞬时变化率",List.of(id))),List.of(Category.LEARNING),List.of("导数")); }
    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void independentWorkersCannotClaimTheSameJob() throws Exception {
        long owner=owner();
        try (var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            store.enqueue(owner,DAY,DUE,false);
            var start=new java.util.concurrent.CountDownLatch(1);
            var first=workers.submit(() -> { start.await(); return store.claim(); });
            var second=workers.submit(() -> { start.await(); return store.claim(); }); start.countDown();
            var claims=List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(claims.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            assertThat(jobs.day(owner,DAY).status()).isEqualTo("RUNNING");
        } finally { jdbc.update("DELETE FROM users WHERE id=?",owner); }
    }
    @Test void storesCompletePagedSnapshotAndScopesItToReadyOwnerDay() {
        long owner=owner(),other=owner(); var ids=new ArrayList<Long>();
        for (int i=0;i<41;i++) ids.add(source(owner,DAY,true));
        source(other,DAY,true); source(owner,DAY.minusDays(1),true); source(owner,DAY,false);
        var lease=lease(owner); store.snapshot(lease);
        var first=store.sources(lease,null); assertThat(first).hasSize(32);
        var last=store.sources(lease,first.getLast().fragmentId()); assertThat(last).hasSize(9);
        assertThat(first).allMatch(row -> row.userId()==owner && row.date().equals(DAY));
        assertThat(first.getFirst().fragmentId()).isEqualTo(ids.getLast());
        String hash=jobs.day(owner,DAY).workingSourceHash();
        source(owner,DAY,true); store.snapshot(lease);
        assertThat(jobs.day(owner,DAY).workingSourceCount()).isEqualTo(41);
        assertThat(jobs.day(owner,DAY).workingSourceHash()).isEqualTo(hash).hasSize(64);
        assertThat(store.complete(lease,piece(owner,41,ids.getFirst()))).isTrue();
        assertThat(jobs.day(owner,DAY).status()).isEqualTo("READY");
        assertThat(store.enqueue(owner,DAY,DUE,false)).isEqualTo(lease.id());
        assertThat(store.claim()).isEmpty();
    }
    @Test void leaseReplacementRejectsStaleWritersAndReusesValidatedCheckpoint() {
        long owner=owner(),id=source(owner,DAY,true); var old=lease(owner); store.snapshot(old);
        String hash="a".repeat(64); assertThat(store.beginCall(old,hash)).isTrue();
        assertThat(store.checkpoint(old,hash,piece(owner,1,id))).isTrue(); expire(old);
        var replacement=store.claim().orElseThrow(); assertThat(replacement.token()).isNotEqualTo(old.token());
        assertThat(replacement.revision()).isEqualTo(old.revision());
        assertThat(store.renew(old)).isFalse(); assertThat(store.fail(old,"DIGEST_AI_UNAVAILABLE")).isFalse();
        assertThat(store.complete(old,piece(owner,1,id))).isFalse();
        assertThat(store.cached(replacement,hash)).contains(piece(owner,1,id));
        assertThat(store.complete(replacement,piece(owner,1,id))).isTrue();
    }
    @Test void unconfirmedPaidCallNeverAutomaticallyRestarts() {
        long owner=owner(),id=source(owner,DAY,true); var lease=lease(owner); store.snapshot(lease);
        assertThat(store.beginCall(lease,"b".repeat(64))).isTrue(); expire(lease);
        assertThat(store.claim()).isEmpty();
        var failed=jobs.day(owner,DAY); assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("DIGEST_AI_UNCONFIRMED"); assertThat(failed.inFlightHash()).isNotNull();
        assertThat(store.claim()).isEmpty();
        store.enqueue(owner,DAY,DUE,true); var retry=store.claim().orElseThrow();
        assertThat(retry.revision()).isEqualTo(2); store.snapshot(retry);
        assertThat(store.complete(retry,piece(owner,1,id))).isTrue();
    }
    @Test void rebuildDuringRunningKeepsLastResultAndQueuesNextRevision() {
        long owner=owner(),id=source(owner,DAY,true); var first=lease(owner); store.snapshot(first); store.complete(first,piece(owner,1,id));
        String result=jobs.day(owner,DAY).resultJson(); store.enqueue(owner,DAY,DUE,true); var second=store.claim().orElseThrow(); store.snapshot(second);
        store.enqueue(owner,DAY,DUE,true);
        assertThat(jobs.day(owner,DAY).requestedRevision()).isEqualTo(3);
        assertThat(jobs.day(owner,DAY).resultJson()).isEqualTo(result);
        assertThat(store.complete(second,piece(owner,1,id))).isTrue();
        assertThat(jobs.day(owner,DAY).status()).isEqualTo("QUEUED");
        assertThat(jobs.day(owner,DAY).completedRevision()).isEqualTo(2);
        assertThat(store.claim().orElseThrow().revision()).isEqualTo(3);
    }
    @Test void rejectsForeignReferencesMismatchedCallsAndIncompleteCoverage() {
        long owner=owner(),other=owner(),id=source(owner,DAY,true),foreign=source(other,DAY,true);
        var lease=lease(owner); store.snapshot(lease);
        assertThat(store.beginCall(lease,"c".repeat(64))).isTrue();
        assertThatThrownBy(() -> store.checkpoint(lease,"c".repeat(64),piece(owner,1,foreign))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.checkpoint(lease,"d".repeat(64),piece(owner,1,id))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.complete(lease,piece(owner,1,id))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.checkpoint(lease,"c".repeat(64),piece(other,1,id))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void emptyDayFinishesWithoutModelCallsAndFailedRebuildRetainsPriorResult() {
        long owner=owner(); var lease=lease(owner); store.snapshot(lease);
        var empty=new DigestPiece(owner,DAY,0,0,"当天暂无已整理完成的内容。",List.of(),List.of(),List.of());
        assertThat(store.complete(lease,empty)).isTrue(); assertThat(jobs.day(owner,DAY).modelCalls()).isZero();
        store.enqueue(owner,DAY,DUE,true); var rebuild=store.claim().orElseThrow();
        assertThat(store.fail(rebuild,"DIGEST_DISABLED")).isTrue();
        assertThat(jobs.day(owner,DAY).completedRevision()).isEqualTo(1);
        assertThat(jobs.day(owner,DAY).resultJson()).contains("当天暂无已整理完成的内容");
    }
}
