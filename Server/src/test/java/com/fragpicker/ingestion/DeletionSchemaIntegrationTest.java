package com.fragpicker.ingestion;

import com.fragpicker.auth.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+")
@Transactional
class DeletionSchemaIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registrations;
    @Autowired SubmissionService submissions;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",()->System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",()->System.getenv("DB_TEST_PASSWORD"));
    }
    @Test void cleanupIdentitySurvivesDeletedParentAndEnforcesLeaseState() {
        long owner=registrations.register(new RegisterRequest("delete_"+UUID.randomUUID().toString().substring(0,8),"Integration-123!")).id();
        var item=submissions.submit(owner,UUID.randomUUID().toString(),new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(),null));
        jdbc.update("INSERT INTO fragment_deletions(fragment_id,user_id,business_date,deleted_at,buckets,status,next_scan_at) SELECT id,user_id,business_date,UTC_TIMESTAMP(3),JSON_ARRAY('fixture'),'QUEUED',UTC_TIMESTAMP(3) FROM fragments WHERE id=?",item.fragmentId());
        for(String change:java.util.List.of("buckets=JSON_OBJECT()","attempts=-1","version=-1","status='RUNNING'","status='CONFIRMED'","status='UNKNOWN'"))
            assertThatThrownBy(()->jdbc.update("UPDATE fragment_deletions SET "+change+" WHERE fragment_id=?",item.fragmentId())).isInstanceOfSatisfying(DataAccessException.class,error->
                assertThat(error.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,sql->assertThat(sql.getErrorCode()).isEqualTo(3819)));
        jdbc.update("DELETE FROM fragments WHERE id=?",item.fragmentId()); jdbc.update("DELETE FROM users WHERE id=?",owner);
        assertThat(jdbc.queryForObject("SELECT buckets FROM fragment_deletions WHERE fragment_id=? AND user_id=?",String.class,item.fragmentId(),owner)).contains("fixture");
    }
}
