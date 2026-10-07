package com.fragpicker.ingestion;

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
class RetrySchemaIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registrations;
    @Autowired SubmissionService submissions;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",() -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",() -> System.getenv("DB_TEST_PASSWORD"));
    }
    private long owner() { return registrations.register(new RegisterRequest("retry_"+UUID.randomUUID().toString().substring(0,8),"Integration-123!")).id(); }
    private void insert(long owner,long fragment,String key,String stage,int confirmed,long version) {
        jdbc.update("INSERT INTO ingestion_retry_requests(user_id,fragment_id,idempotency_key,request_hash,next_stage,confirmed_new_transcription,job_version) VALUES (?,?,?,REPEAT('a',64),?,?,?)",owner,fragment,key,stage,confirmed,version);
    }
    @Test void enforcesOwnershipIdentityStagesConfirmationAndCascade() {
        long owner=owner(),foreign=owner(); long fragment=submissions.submit(owner,UUID.randomUUID().toString(),new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(),null)).fragmentId();
        var key=UUID.randomUUID().toString(); insert(owner,fragment,key,"QUEUED",0,1);
        assertThatThrownBy(() -> insert(owner,fragment,key,"QUEUED",0,2)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert(foreign,fragment,UUID.randomUUID().toString(),"QUEUED",0,1)).isInstanceOf(DataAccessException.class);
        for (String invalid:java.util.List.of("next_stage='RUNNING'","confirmed_new_transcription=2","job_version=0"))
            assertThatThrownBy(() -> jdbc.update("UPDATE ingestion_retry_requests SET "+invalid+" WHERE user_id=?",owner)).isInstanceOfSatisfying(DataAccessException.class,error ->
                assertThat(error.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,sql -> assertThat(sql.getErrorCode()).isEqualTo(3819)));
        jdbc.update("DELETE FROM fragments WHERE id=?",fragment);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_retry_requests WHERE user_id=?",Long.class,owner)).isZero();
    }
}
