package com.fragpicker.digest;

import com.fragpicker.auth.*;
import com.fragpicker.ingestion.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
@Transactional
class DigestSchemaIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registrations;
    @Autowired SubmissionService submissions;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }
    private long owner() { return registrations.register(new RegisterRequest("digest_" + UUID.randomUUID().toString().substring(0, 8), "Integration-123!")).id(); }
    private long digest(long owner) {
        jdbc.update("INSERT INTO daily_digests(user_id,business_date,next_run_at) VALUES (?,'2026-10-06',UTC_TIMESTAMP(3))", owner);
        return jdbc.queryForObject("SELECT id FROM daily_digests WHERE user_id = ?", Long.class, owner);
    }
    @Test void rejectsDuplicateDaysAndInvalidLeaseOrRevision() {
        long owner = owner(), digest = digest(owner);
        assertThatThrownBy(() -> digest(owner)).isInstanceOf(DataIntegrityViolationException.class);
        rejectsCheck(() -> jdbc.update("UPDATE daily_digests SET status='RUNNING' WHERE id=?", digest));
        rejectsCheck(() -> jdbc.update("UPDATE daily_digests SET working_revision=2 WHERE id=?", digest));
        jdbc.update("UPDATE daily_digests SET status='RUNNING',working_revision=1,lease_token=?,lease_expires_at=UTC_TIMESTAMP(3)+INTERVAL 1 MINUTE WHERE id=?", UUID.randomUUID().toString(), digest);
        rejectsCheck(() -> jdbc.update("UPDATE daily_digests SET status='READY' WHERE id=?", digest));
    }
    @Test void enforcesSourceCheckpointRequestOwnershipAndCascade() {
        long owner = owner(), other = owner(), digest = digest(owner);
        var own = submissions.submit(owner, UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(), null));
        var foreign = submissions.submit(other, UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(), null));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO daily_digest_sources VALUES (?,?,1,?,JSON_OBJECT())", digest, owner, foreign.fragmentId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO daily_digest_checkpoints(digest_id,user_id,revision,request_hash,piece_json) VALUES (?,?,1,REPEAT('a',64),JSON_OBJECT())", digest, other)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO daily_digest_requests(user_id,idempotency_key,digest_id,revision) VALUES (?,?,?,1)", other, UUID.randomUUID().toString(), digest)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO daily_digest_sources VALUES (?,?,1,?,JSON_OBJECT())", digest, owner, own.fragmentId());
        jdbc.update("INSERT INTO daily_digest_checkpoints(digest_id,user_id,revision,request_hash,piece_json) VALUES (?,?,1,REPEAT('a',64),JSON_OBJECT())", digest, owner);
        jdbc.update("INSERT INTO daily_digest_requests(user_id,idempotency_key,digest_id,revision) VALUES (?,?,?,1)", owner, UUID.randomUUID().toString(), digest);
        rejectsCheck(() -> jdbc.update("INSERT INTO daily_digest_checkpoints(digest_id,user_id,revision,request_hash,piece_json) VALUES (?,?,1,REPEAT('b',64),JSON_ARRAY())", digest, owner));
        jdbc.update("DELETE FROM users WHERE id=?", owner);
        for (String table : java.util.List.of("daily_digests", "daily_digest_sources", "daily_digest_checkpoints", "daily_digest_requests")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, owner)).isZero();
        }
    }
    private static void rejectsCheck(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(DataAccessException.class, error ->
            assertThat(error.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class, sql ->
                assertThat(sql.getErrorCode()).isEqualTo(3819)));
    }
}
