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
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("6");
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

    @Test
    void upgradesExistingMediaAndKnowledgeWithoutLosingOwnerStageOrOriginalContent() throws Exception {
        String schema = "fragpicker_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?');
        String address = query < 0 ? original : original.substring(0, query);
        String url = address.substring(0, address.lastIndexOf('/') + 1) + schema + (query < 0 ? "" : original.substring(query));
        String username = System.getenv("DB_TEST_USERNAME"), password = System.getenv("DB_TEST_PASSWORD");
        try (var admin = java.sql.DriverManager.getConnection(original, username, password); var ddl = admin.createStatement()) {
            ddl.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                Flyway.configure().dataSource(url, username, password).target("4").load().migrate();
                try (var connection = java.sql.DriverManager.getConnection(url, username, password); var statement = connection.createStatement()) {
                    statement.execute("INSERT INTO users (id, username, username_normalized, password_hash) VALUES (1, 'upgrade', 'upgrade', 'test-placeholder')");
                    statement.execute("INSERT INTO fragments (id, user_id, source_url, source_hash, source_host, business_date, business_zone, status, created_at) VALUES (1, 1, 'https://b23.tv/test', REPEAT('a', 64), 'b23.tv', '2026-10-06', 'Asia/Shanghai', 'TRANSCRIPTION_PENDING', UTC_TIMESTAMP(3))");
                    statement.execute("INSERT INTO ingestion_jobs (id, fragment_id, user_id, stage, attempt_count, media_attempt_count, next_attempt_at, created_at) VALUES (1, 1, 1, 'TRANSCRIPTION_PENDING', 2, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
                }
                assertThat(Flyway.configure().dataSource(url, username, password).target("5").load().migrate().migrationsExecuted).isEqualTo(1);
                try (var connection = java.sql.DriverManager.getConnection(url, username, password); var statement = connection.createStatement();
                     var row = statement.executeQuery("SELECT user_id, stage, attempt_count, media_attempt_count, transcription_failures FROM ingestion_jobs WHERE id = 1")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getLong("user_id")).isEqualTo(1);
                    assertThat(row.getString("stage")).isEqualTo("TRANSCRIPTION_PENDING");
                    assertThat(row.getInt("attempt_count")).isEqualTo(2); assertThat(row.getInt("media_attempt_count")).isEqualTo(1);
                    assertThat(row.getInt("transcription_failures")).isZero();
                }
                try (var connection = java.sql.DriverManager.getConnection(url, username, password); var statement = connection.createStatement()) {
                    statement.execute("INSERT INTO fragment_knowledge (fragment_id, user_id, duration_ms, summary, keywords, completed_at) VALUES (1, 1, 2000, '原始导数摘要', JSON_ARRAY('导数'), UTC_TIMESTAMP(3))");
                }
                assertThat(Flyway.configure().dataSource(url, username, password).target("6").load().migrate().migrationsExecuted).isEqualTo(1);
                try (var connection = java.sql.DriverManager.getConnection(url, username, password); var statement = connection.createStatement();
                     var row = statement.executeQuery("SELECT k.summary, k.keywords, k.enriched_summary, k.categories, j.knowledge_attempt_count FROM fragment_knowledge k JOIN ingestion_jobs j ON j.fragment_id = k.fragment_id WHERE k.fragment_id = 1")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getString("summary")).isEqualTo("原始导数摘要");
                    assertThat(row.getString("keywords")).contains("导数"); assertThat(row.getString("enriched_summary")).isNull();
                    assertThat(row.getString("categories")).isNull(); assertThat(row.getInt("knowledge_attempt_count")).isZero();
                }
            } finally { ddl.execute("DROP DATABASE " + schema); }
        }
    }

    private UserAccount account(String displayName, String normalized) {
        var user = new UserAccount();
        user.setUsername(displayName);
        user.setUsernameNormalized(normalized);
        user.setPasswordHash("test-only-placeholder-not-an-actual-password-hash");
        return user;
    }
}
