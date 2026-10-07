package com.fragpicker.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fragpicker.auth.*;
import com.fragpicker.ingestion.retry.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+")
class RetryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired RegistrationService registrations;
    @Autowired LoginService logins;
    @Autowired SubmissionService submissions;
    @Autowired RetryService retries;
    @Autowired TranscriptionJobStore transcriptions;
    final List<Long> users=new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",()->System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",()->System.getenv("DB_TEST_PASSWORD"));
    }
    record Owner(long id,String token) { }
    Owner owner() {
        var name="retry_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        long id=registrations.register(new RegisterRequest(name,"Integration-123!")).id();users.add(id);
        return new Owner(id,logins.login(new LoginRequest(name,"Integration-123!")).accessToken());
    }
    SubmissionResponse failed(Owner owner,String code) {
        var item=submissions.submit(owner.id(),UUID.randomUUID().toString(),new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(),"学习数学"));
        jdbc.update("UPDATE ingestion_jobs SET stage='FAILED',error_code=?,attempt_count=3,media_attempt_count=3,transcription_failures=8,knowledge_attempt_count=3,index_attempt_count=3 WHERE id=?",code,item.jobId());
        jdbc.update("UPDATE fragments SET status='FAILED' WHERE id=?",item.fragmentId());return item;
    }
    void media(Owner owner,SubmissionResponse item,boolean coverRequired,boolean cover) {
        jdbc.update("INSERT INTO fragment_video_metadata(fragment_id,user_id,video_url,cover_url,parsed_at) VALUES (?,?, 'https://example.com/video',?,UTC_TIMESTAMP(3))",item.fragmentId(),owner.id(),coverRequired?"https://example.com/cover":null);
        for(String kind:cover?List.of("VIDEO","COVER"):List.of("VIDEO"))
            jdbc.update("INSERT INTO fragment_stored_media(fragment_id,user_id,kind,bucket,object_key,size_bytes,sha256,content_type,stored_at) VALUES (?,?,?,'fixture',?,3,REPEAT('a',64),'application/octet-stream',UTC_TIMESTAMP(3))",item.fragmentId(),owner.id(),kind,"users/"+owner.id()+"/fragments/"+item.fragmentId()+"/"+kind.toLowerCase()+"/"+"a".repeat(64));
    }
    void task(Owner owner,SubmissionResponse item,String id) {
        jdbc.update("INSERT INTO fragment_transcriptions(fragment_id,user_id,task_key,task_id,submitted_at) VALUES (?,?,?,?,'2000-01-01')",item.fragmentId(),owner.id(),"key-"+item.fragmentId(),id);
    }
    ResponseEntity<JsonNode> post(Owner owner,long id,String key,boolean replace) {
        var headers=new HttpHeaders();headers.setBearerAuth(owner.token());headers.set("Idempotency-Key",key);headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/api/v1/fragments/"+id+"/retry",new HttpEntity<>(Map.of("replaceTranscription",replace),headers),JsonNode.class);
    }
    @AfterEach void clean() { for(long owner:users) jdbc.update("DELETE FROM users WHERE id=?",owner); }
    @Test void selectsPersistedStageAndResetsOnlyUnfinishedBudgets() {
        var owner=owner();var parsed=failed(owner,"PARSE_FAILED");
        var response=post(owner,parsed.fragmentId(),UUID.randomUUID().toString(),false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);assertThat(response.getBody().path("nextStage").asText()).isEqualTo("QUEUED");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var media=failed(owner,"MEDIA_FAILED");media(owner,media,true,false);
        assertThat(retries.retry(owner.id(),media.fragmentId(),UUID.randomUUID().toString(),new RetryRequest(false)).nextStage()).isEqualTo("MEDIA_PENDING");
        var readyMedia=failed(owner,"TINGWU_ACCESS_DENIED");media(owner,readyMedia,false,false);
        assertThat(retries.retry(owner.id(),readyMedia.fragmentId(),UUID.randomUUID().toString(),new RetryRequest(false)).nextStage()).isEqualTo("TRANSCRIPTION_PENDING");
        for(boolean enriched:List.of(false,true)) {
            var item=failed(owner,"KNOWLEDGE_FAILED");
            jdbc.update("INSERT INTO fragment_knowledge(fragment_id,user_id,duration_ms,summary,keywords,completed_at,enriched_at) VALUES (?,?,1000,'保存的转写摘要',JSON_ARRAY(),UTC_TIMESTAMP(3),?)",item.fragmentId(),owner.id(),enriched?LocalDateTime.now():null);
            var result=retries.retry(owner.id(),item.fragmentId(),UUID.randomUUID().toString(),new RetryRequest(false));
            assertThat(result.nextStage()).isEqualTo(enriched?"INDEX_PENDING":"KNOWLEDGE_PENDING");
            var row=jdbc.queryForMap("SELECT attempt_count,transcription_failures,knowledge_attempt_count,index_attempt_count,lease_owner,error_code FROM ingestion_jobs WHERE id=?",item.jobId());
            assertThat(row.get("attempt_count")).isEqualTo(3);assertThat(row.get("transcription_failures")).isEqualTo(8);
            assertThat(row.get("knowledge_attempt_count")).isEqualTo(enriched?3:0);assertThat(row.get("index_attempt_count")).isEqualTo(0);
            assertThat(row.get("lease_owner")).isNull();assertThat(row.get("error_code")).isNull();
            assertThat(jdbc.queryForObject("SELECT summary FROM fragment_knowledge WHERE fragment_id=?",String.class,item.fragmentId())).isEqualTo("保存的转写摘要");
        }
    }
    @Test void resumesSameKnownTaskAndExtendsQueryWindowWithoutChangingSubmission() {
        var owner=owner();var item=failed(owner,"TINGWU_TASK_TIMEOUT");media(owner,item,false,false);task(owner,item,"old-cloud-id");
        var result=retries.retry(owner.id(),item.fragmentId(),UUID.randomUUID().toString(),new RetryRequest(false));
        assertThat(result.nextStage()).isEqualTo("TRANSCRIBING");
        var saved=jdbc.queryForMap("SELECT task_id,submitted_at,requery_at FROM fragment_transcriptions WHERE fragment_id=?",item.fragmentId());
        assertThat(saved.get("task_id")).isEqualTo("old-cloud-id");assertThat(saved.get("submitted_at").toString()).startsWith("2000-01-01");assertThat(saved.get("requery_at")).isNotNull();
        var lease=transcriptions.claim().orElseThrow();assertThat(lease.fragmentId()).isEqualTo(item.fragmentId());
        assertThat(transcriptions.task(lease).taskId()).isEqualTo("old-cloud-id");
        assertThat(transcriptions.ongoing(lease)).isTrue();
    }
    @Test void uncertainAndTerminalTasksRequireExplicitConfirmationAndAuditOldIdentity() {
        var owner=owner();
        for(String code:List.of("TINGWU_SUBMISSION_UNCERTAIN","TINGWU_TASK_UNSUPPORTED_MEDIA","TINGWU_NO_SPEECH")) {
            var item=failed(owner,code);media(owner,item,false,false);task(owner,item,code.equals("TINGWU_SUBMISSION_UNCERTAIN")?null:"task-"+item.fragmentId());
            var headers=new HttpHeaders();headers.setBearerAuth(owner.token());
            var status=http.exchange("/api/v1/fragments/"+item.fragmentId(),HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getBody();
            assertThat(status.path("canRetry").asBoolean()).isTrue();assertThat(status.path("retryRequiresNewTranscription").asBoolean()).isTrue();
            var key=UUID.randomUUID().toString();var refused=post(owner,item.fragmentId(),key,false);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(refused.getBody().path("code").asText()).isEqualTo("RETRY_CONFIRM_TRANSCRIPTION");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_retry_requests WHERE fragment_id=?",Integer.class,item.fragmentId())).isZero();
            assertThat(post(owner,item.fragmentId(),key,true).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_transcriptions WHERE fragment_id=?",Integer.class,item.fragmentId())).isZero();
            assertThat(jdbc.queryForObject("SELECT previous_task_key FROM ingestion_retry_requests WHERE fragment_id=?",String.class,item.fragmentId())).isEqualTo("key-"+item.fragmentId());
            var old=new TranscriptionLease(item.jobId(),item.fragmentId(),owner.id(),0,"old-worker");
            assertThat(transcriptions.recordTask(old,"key-"+item.fragmentId(),"late-id")).isFalse();
            assertThat(post(owner,item.fragmentId(),key,true).getBody().path("duplicate").asBoolean()).isTrue();
            assertThat(post(owner,item.fragmentId(),key,false).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }
    @Test void replaySurvivesProgressAndConcurrentRequestsQueueOnlyOnce() throws Exception {
        var owner=owner();var item=failed(owner,"PARSE_FAILED");var key=UUID.randomUUID().toString();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{ start.await();return retries.retry(owner.id(),item.fragmentId(),key,new RetryRequest(false)); });
            var b=pool.submit(()->{ start.await();return retries.retry(owner.id(),item.fragmentId(),key,new RetryRequest(false)); });start.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)).stream().filter(RetryResponse::duplicate)).hasSize(1);
        }
        jdbc.update("UPDATE ingestion_jobs SET stage='PARSING' WHERE id=?",item.jobId());
        assertThat(post(owner,item.fragmentId(),key,false).getBody().path("status").asText()).isEqualTo("PARSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_retry_requests WHERE fragment_id=?",Integer.class,item.fragmentId())).isEqualTo(1);
        jdbc.update("UPDATE ingestion_jobs SET stage='FAILED' WHERE id=?",item.jobId());
        assertThat(post(owner,item.fragmentId(),UUID.randomUUID().toString(),false).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
    @Test void enforcesAuthenticationOwnershipValidationAndFailedOnly() {
        var owner=owner();var stranger=owner();var item=failed(owner,"PARSE_FAILED");
        assertThat(http.postForEntity("/api/v1/fragments/"+item.fragmentId()+"/retry",Map.of("replaceTranscription",false),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(stranger,item.fragmentId(),UUID.randomUUID().toString(),false).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(owner,item.fragmentId(),"invalid",false).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        jdbc.update("UPDATE ingestion_jobs SET stage='READY' WHERE id=?",item.jobId());
        assertThat(post(owner,item.fragmentId(),UUID.randomUUID().toString(),false).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
