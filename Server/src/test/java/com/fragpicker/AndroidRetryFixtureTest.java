package com.fragpicker;

import com.fragpicker.auth.*;
import com.fragpicker.ingestion.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit fixture flag, disposable schema only, no cloud or model operations. */
@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+/fragpicker_test\\?.+")
@EnabledIfEnvironmentVariable(named="ANDROID_RETRY_FIXTURE",matches="true")
class AndroidRetryFixtureTest {
    @Autowired RegistrationService registrations;
    @Autowired SubmissionService submissions;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",()->System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",()->System.getenv("DB_TEST_PASSWORD"));
    }
    @Test void preparesFailedPhaseCheckpointsForDeviceRetry() {
        for(String name:java.util.List.of("parse","uncertain","resume")) {
            long owner=registrations.register(new RegisterRequest("android_retry_"+name,"Android-fixture-123!")).id();
            var item=submissions.submit(owner,UUID.randomUUID().toString(),new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(),"设备重试夹具"));
            String code=name.equals("parse")?"PARSE_INVALID_RESPONSE":name.equals("uncertain")?"TINGWU_SUBMISSION_UNCERTAIN":"TINGWU_TASK_TIMEOUT";
            jdbc.update("UPDATE fragments SET status='FAILED' WHERE id=?",item.fragmentId());
            jdbc.update("UPDATE ingestion_jobs SET stage='FAILED',error_code=? WHERE id=?",code,item.jobId());
            if (!name.equals("parse")) {
                jdbc.update("INSERT INTO fragment_video_metadata(fragment_id,user_id,title,video_url,parsed_at) VALUES (?,?,'需要恢复的视频','https://example.com/test-only.mp4',UTC_TIMESTAMP(3))",item.fragmentId(),owner);
                jdbc.update("INSERT INTO fragment_stored_media(fragment_id,user_id,kind,bucket,object_key,size_bytes,sha256,content_type,stored_at) VALUES (?,?,'VIDEO','fixture',?,3,REPEAT('a',64),'video/mp4',UTC_TIMESTAMP(3))",item.fragmentId(),owner,"users/"+owner+"/fragments/"+item.fragmentId()+"/video/"+"a".repeat(64));
                jdbc.update("INSERT INTO fragment_transcriptions(fragment_id,user_id,task_key,task_id,submitted_at) VALUES (?,?,?,?, '2000-01-01')",item.fragmentId(),owner,"intent-"+item.fragmentId(),name.equals("resume")?"original-cloud-id":null);
            }
            assertThat(jdbc.queryForObject("SELECT stage FROM ingestion_jobs WHERE id=?",String.class,item.jobId())).isEqualTo("FAILED");
        }
    }
}
