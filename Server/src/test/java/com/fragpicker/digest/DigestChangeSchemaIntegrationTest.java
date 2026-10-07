package com.fragpicker.digest;

import com.fragpicker.auth.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+")
@Transactional
class DigestChangeSchemaIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registrations;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",() -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",() -> System.getenv("DB_TEST_PASSWORD"));
    }
    @Test void versionsOwnershipAndAccountDeletionAreEnforced() {
        long owner=registrations.register(new RegisterRequest("change_"+UUID.randomUUID().toString().substring(0,8),"Integration-123!")).id();
        jdbc.update("INSERT INTO daily_digest_changes(user_id,business_date,due_at) VALUES (?,'2026-10-07',UTC_TIMESTAMP(3))",owner);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO daily_digest_changes(user_id,business_date,due_at) VALUES (?,'2026-10-07',UTC_TIMESTAMP(3))",owner)).isInstanceOf(DataAccessException.class);
        for (String invalid:java.util.List.of("version=0","scheduled_version=2","scheduled_version=-1")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE daily_digest_changes SET "+invalid+" WHERE user_id=?",owner)).isInstanceOfSatisfying(DataAccessException.class,error ->
                assertThat(error.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,sql -> assertThat(sql.getErrorCode()).isEqualTo(3819)));
        }
        assertThatThrownBy(() -> jdbc.update("INSERT INTO daily_digest_changes(user_id,business_date,due_at) VALUES (9223372036854775807,'2026-10-07',UTC_TIMESTAMP(3))")).isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE daily_digest_changes SET scheduled_version=version WHERE user_id=?",owner);
        jdbc.update("DELETE FROM users WHERE id=?",owner);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_digest_changes WHERE user_id=?",Integer.class,owner)).isZero();
    }
}
