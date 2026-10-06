package com.fragpicker;

import com.fragpicker.user.UserAccount;
import com.fragpicker.user.UserAccountMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
@Transactional
class DatabaseMigrationTest {
    @Autowired private UserAccountMapper users;
    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }

    @Test
    void migratesEmptyMySqlAndSecondMigrationIsNoOp() {
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("4");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name IN ('users', 'refresh_tokens')", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void mapperRoundTripsChineseMetadataAndDatabaseDefaults() {
        var user = account("Test_User", "test_user");
        assertThat(users.insert(user)).isEqualTo(1);
        var stored = users.selectById(user.getId());
        assertThat(stored.getUsername()).isEqualTo("Test_User");
        assertThat(stored.getBusinessZone()).isEqualTo("Asia/Shanghai");
        assertThat(stored.getEnabled()).isTrue();
        assertThat(stored.getTokenVersion()).isZero();
        assertThat(stored.getCreatedAt()).isNotNull();
    }

    @Test
    void enforcesNormalizedUsernameUniqueness() {
        users.insert(account("First", "unique_name"));
        assertThatThrownBy(() -> users.insert(account("Second", "unique_name")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private UserAccount account(String displayName, String normalized) {
        var user = new UserAccount();
        user.setUsername(displayName);
        user.setUsernameNormalized(normalized);
        user.setPasswordHash("test-only-placeholder-not-an-actual-password-hash");
        return user;
    }
}
