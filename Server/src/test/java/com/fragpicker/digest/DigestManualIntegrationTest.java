package com.fragpicker.digest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fragpicker.ingestion.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"fragpicker.digest.worker.enabled=false","fragpicker.digest.schedule.enabled=false"})
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+")
class DigestManualIntegrationTest {
    private static final LocalDate DAY=LocalDate.of(2026,10,5);
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubmissionService submissions;
    @Autowired DigestStore store;
    @Autowired DigestMapper jobs;
    @MockitoBean DigestJobProperties worker;
    private final List<Long> owners=new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",() -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",() -> System.getenv("DB_TEST_PASSWORD"));
    }
    @BeforeEach void enabled() { when(worker.enabled()).thenReturn(true); when(worker.leaseDuration()).thenReturn(Duration.ofMinutes(5)); }
    @AfterEach void clean() { owners.forEach(owner -> jdbc.update("DELETE FROM users WHERE id=?",owner)); }
    private Account login() {
        var credentials=Map.of("username","manual_"+UUID.randomUUID().toString().substring(0,8),"password","Integration-123!");
        var registered=http.postForEntity("/api/v1/auth/register",credentials,JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long owner=registered.getBody().path("id").asLong(); owners.add(owner);
        var login=http.postForEntity("/api/v1/auth/login",credentials,JsonNode.class); return new Account(owner,login.getBody().path("accessToken").asText());
    }
    private void seed(Account owner) {
        long id=submissions.submit(owner.id(),UUID.randomUUID().toString(),new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(),null)).fragmentId();
        jdbc.update("UPDATE fragments SET business_date=? WHERE id=?",DAY,id);
    }
    private ResponseEntity<JsonNode> post(Account owner,String date,String key) {
        var headers=new HttpHeaders(); headers.setBearerAuth(owner.token()); if (key!=null) headers.set("Idempotency-Key",key);
        return http.exchange("/api/v1/daily-digests/"+date+"/regenerate",HttpMethod.POST,new HttpEntity<>(headers),JsonNode.class);
    }
    private ResponseEntity<JsonNode> post(Account owner,String key) { return post(owner,DAY.toString(),key); }
    private void cooldown(Account owner) { jdbc.update("UPDATE daily_digests SET last_manual_at='2020-01-01' WHERE user_id=?",owner.id()); }
    @Test void queuesAsyncAndSameKeyReconcilesInsideCooldownIncludingDisabledWorker() {
        var owner=login(); seed(owner); var key=UUID.randomUUID().toString(); var first=post(owner,key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); assertThat(first.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(first.getBody().path("revision").asLong()).isEqualTo(1); assertThat(first.getBody().path("duplicate").asBoolean()).isFalse();
        var replay=post(owner,key.toUpperCase()); assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); assertThat(replay.getBody().path("duplicate").asBoolean()).isTrue();
        assertThat(post(owner,UUID.randomUUID().toString()).getBody().path("code").asText()).isEqualTo("DIGEST_REBUILD_COOLDOWN");
        when(worker.enabled()).thenReturn(false); assertThat(post(owner,key).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_digest_requests WHERE user_id=?",Long.class,owner.id())).isEqualTo(1);
        assertThat(jobs.day(owner.id(),DAY).requestedRevision()).isEqualTo(1);
    }
    @Test void validatesKeyFutureDateEmptyDayAndDisabledWithoutCreatingJobs() {
        var owner=login(); seed(owner);
        assertThat(post(owner,null).getBody().path("code").asText()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
        assertThat(post(owner,"bad").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(owner,"9999-12-31",UUID.randomUUID().toString()).getBody().path("code").asText()).isEqualTo("INVALID_DIGEST_DATE");
        assertThat(post(owner,"2020-01-01",UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(worker.enabled()).thenReturn(false); assertThat(post(owner,UUID.randomUUID().toString()).getBody().path("code").asText()).isEqualTo("DIGEST_DISABLED");
        assertThat(jobs.day(owner.id(),DAY)).isNull();
        assertThat(http.postForEntity("/api/v1/daily-digests/2026-10-05/regenerate",null,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void differentDayConflictsAndForeignUserCannotGenerateAnotherUsersDay() {
        var owner=login(); var foreign=login(); seed(owner); var key=UUID.randomUUID().toString(); post(owner,key);
        assertThat(post(owner,"2026-10-04",key).getBody().path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(post(foreign,key).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND); assertThat(jobs.day(foreign.id(),DAY)).isNull();
    }
    @Test void queuedAndRunningRebuildsAreCoalescedAndExpiredCallsAreExplicitlyFenced() {
        var owner=login(); seed(owner); post(owner,UUID.randomUUID().toString()); cooldown(owner);
        post(owner,UUID.randomUUID().toString()); assertThat(jobs.day(owner.id(),DAY).requestedRevision()).isEqualTo(1);
        var lease=store.claim().orElseThrow(); cooldown(owner); post(owner,UUID.randomUUID().toString());
        assertThat(jobs.day(owner.id(),DAY).requestedRevision()).isEqualTo(2); cooldown(owner); post(owner,UUID.randomUUID().toString());
        assertThat(jobs.day(owner.id(),DAY).requestedRevision()).isEqualTo(2);
        store.snapshot(lease); assertThat(store.beginCall(lease,"a".repeat(64))).isTrue();
        jdbc.update("UPDATE daily_digests SET lease_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",lease.id());
        cooldown(owner); var retry=post(owner,UUID.randomUUID().toString()); assertThat(retry.getBody().path("status").asText()).isEqualTo("QUEUED");
        assertThat(jobs.day(owner.id(),DAY).requestedRevision()).isEqualTo(3); assertThat(jobs.day(owner.id(),DAY).inFlightHash()).isNull();
        assertThat(store.renew(lease)).isFalse(); assertThat(store.claim().orElseThrow().revision()).isEqualTo(3);
    }
    @Test void concurrentSameKeyCommitsOneRequestAndOneRevision() throws Exception {
        var owner=login(); seed(owner); var key=UUID.randomUUID().toString();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> post(owner,key)); var second=pool.submit(() -> post(owner,key));
            assertThat(first.get(10,TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(second.get(10,TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_digest_requests WHERE user_id=?",Long.class,owner.id())).isEqualTo(1);
        assertThat(jobs.day(owner.id(),DAY).requestedRevision()).isEqualTo(1);
    }
    private record Account(long id,String token) { @Override public String toString() { return "Account[REDACTED]"; } }
}
