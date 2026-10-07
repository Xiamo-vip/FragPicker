package com.fragpicker.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fragpicker.auth.*;
import com.fragpicker.chat.*;
import com.fragpicker.chat.persistence.ChatTurnStore;
import com.fragpicker.chat.turn.ChatTurnResult;
import com.fragpicker.digest.*;
import com.fragpicker.ingestion.deletion.*;
import com.fragpicker.knowledge.EnrichmentResult.Category;
import com.fragpicker.knowledge.search.SearchResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+")
class DeletionIntegrationTest {
    @Autowired RegistrationService registrations;
    @Autowired LoginService logins;
    @Autowired SubmissionService submissions;
    @Autowired ParseJobStore parser;
    @Autowired DeletionService deletions;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired DigestStore digests;
    @Autowired DigestScheduleStore schedule;
    @Autowired ChatSessionService sessions;
    @Autowired ChatTurnStore turns;
    @Autowired com.fragpicker.knowledge.tool.HistoryToolFactory tools;
    private final List<Long> owners=new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",()->System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",()->System.getenv("DB_TEST_PASSWORD"));
        registry.add("fragpicker.integrations.oss.bucket",()->"fixture-private");
    }
    record Owner(long id,String token) { }
    Owner owner() {
        String name="delete_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        long id=registrations.register(new RegisterRequest(name,"Integration-123!")).id();owners.add(id);
        return new Owner(id,logins.login(new LoginRequest(name,"Integration-123!")).accessToken());
    }
    SubmissionResponse source(Owner owner) { return submissions.submit(owner.id(),UUID.randomUUID().toString(),new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(),"删除测试")); }
    LocalDate day(long fragment) { return jdbc.queryForObject("SELECT business_date FROM fragments WHERE id=?",LocalDate.class,fragment); }
    void ready(Owner owner,SubmissionResponse item) {
        jdbc.update("UPDATE fragments SET status='READY' WHERE id=?",item.fragmentId());jdbc.update("UPDATE ingestion_jobs SET stage='READY' WHERE id=?",item.jobId());
        jdbc.update("INSERT INTO fragment_knowledge(fragment_id,user_id,duration_ms,summary,keywords,completed_at,enriched_summary,bullet_points,categories,enriched_at) VALUES (?,?,1000,'原文秘密',JSON_ARRAY('导数'),UTC_TIMESTAMP(3),'导数学习摘要',JSON_ARRAY('知识点'),JSON_ARRAY('LEARNING'),UTC_TIMESTAMP(3))",item.fragmentId(),owner.id());
        jdbc.update("INSERT INTO fragment_indexes(fragment_id,user_id,model_id,chunker_version,dimensions,chunk_count,indexed_at) VALUES (?,?,'fixture','fixture',512,1,UTC_TIMESTAMP(3))",item.fragmentId(),owner.id());
        jdbc.update("INSERT INTO fragment_index_chunks(fragment_id,user_id,ordinal,source_kind,content,embedding) VALUES (?,?,0,'SUMMARY','导数',?)",item.fragmentId(),owner.id(),new byte[2048]);
        jdbc.update("INSERT INTO fragment_stored_media(fragment_id,user_id,kind,bucket,object_key,size_bytes,sha256,content_type,stored_at) VALUES (?,?,'VIDEO','fixture-private',?,3,REPEAT('a',64),'video/mp4',UTC_TIMESTAMP(3))",item.fragmentId(),owner.id(),"users/"+owner.id()+"/fragments/"+item.fragmentId()+"/video/"+"a".repeat(64));
    }
    DigestLease snapshot(Owner owner,SubmissionResponse item) {
        digests.enqueue(owner.id(),day(item.fragmentId()),LocalDateTime.of(2000,1,1,0,0),false);var lease=digests.claim().orElseThrow();digests.snapshot(lease);return lease;
    }
    ResponseEntity<JsonNode> delete(Owner owner,long id) { var headers=new HttpHeaders();headers.setBearerAuth(owner.token());return http.exchange("/api/v1/fragments/"+id,HttpMethod.DELETE,new HttpEntity<>(headers),JsonNode.class); }
    @AfterEach void clean() {
        for(long owner:owners) { jdbc.update("DELETE FROM users WHERE id=?",owner);jdbc.update("DELETE FROM fragment_deletions WHERE user_id=?",owner); }
    }
    @Test void deleteCascadesKnowledgeIndexAndCardsButRetainsCleanupAndConversation() {
        var owner=owner();var item=source(owner);ready(owner,item);var date=day(item.fragmentId());
        var lease=snapshot(owner,item);var piece=new DigestPiece(owner.id(),date,1,1,"本日秘密摘要",List.of(new DigestPoint("知识点",List.of(item.fragmentId()))),List.of(Category.LEARNING),List.of("导数"));
        assertThat(digests.beginCall(lease,"a".repeat(64))).isTrue();assertThat(digests.checkpoint(lease,"a".repeat(64),piece)).isTrue();assertThat(digests.complete(lease,piece)).isTrue();
        long session=sessions.create(owner.id(),UUID.randomUUID().toString(),new CreateSessionRequest("学习")).sessionId();var ticket=turns.begin(owner.id(),session,UUID.randomUUID().toString(),"查找导数").turn();
        var card=new SearchResponse.Hit(item.fragmentId(),"导数课程",null,date,"摘要",List.of(Category.LEARNING),null,null,.8,.8,true,new SearchResponse.Match(0,"SUMMARY",null,null,null,"导数"));
        assertThat(turns.complete(ticket,new ChatTurnResult("找到课程",List.of(card),false,2,1))).isTrue();
        var response=delete(owner,item.fragmentId());assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(response.getBody().path("cleanupPending").asBoolean()).isTrue();
        for(String table:List.of("fragments","ingestion_jobs","fragment_knowledge","fragment_indexes","fragment_index_chunks","fragment_stored_media","chat_turn_sources")) {
            String key=table.equals("fragments")?"id":"fragment_id";
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+key+"=?",Integer.class,item.fragmentId())).as(table).isZero();
        }
        assertThat(turns.get(owner.id(),session,ticket.id()).answer()).isEqualTo("找到课程");assertThat(turns.get(owner.id(),session,ticket.id()).cards()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_digests WHERE user_id=?",Integer.class,owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_digest_checkpoints WHERE user_id=?",Integer.class,owner.id())).isZero();
        var tool=tools.bind(new CurrentUser(owner.id())).execute(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("day").name("getDailyDigest").arguments("{\"date\":\""+date+"\"}").build());
        assertThat(tool).contains("EMPTY").doesNotContain("秘密");
        var before=jdbc.queryForMap("SELECT buckets,version,next_scan_at FROM fragment_deletions WHERE fragment_id=?",item.fragmentId());
        assertThat(delete(owner,item.fragmentId()).getBody().path("duplicate").asBoolean()).isTrue();
        assertThat(jdbc.queryForMap("SELECT buckets,version,next_scan_at FROM fragment_deletions WHERE fragment_id=?",item.fragmentId())).isEqualTo(before);
        var candidate=new DigestChange(owner.id(),date,0,0,LocalDateTime.of(2000,1,1,0,0));
        jdbc.update("UPDATE daily_digest_changes SET due_at='2000-01-01' WHERE user_id=?",owner.id());assertThat(schedule.dispatch(candidate)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_digests WHERE user_id=?",Integer.class,owner.id())).isZero();
    }
    @Test void invalidatesAllDailySnapshotsAndFencesUnknownPaidCallWithoutAutoRegeneration() {
        var owner=owner();var a=source(owner);var b=source(owner);ready(owner,a);ready(owner,b);
        var lease=snapshot(owner,a);assertThat(digests.beginCall(lease,"b".repeat(64))).isTrue();
        delete(owner,a.fragmentId());var row=jdbc.queryForMap("SELECT status,error_code,result_json,lease_token FROM daily_digests WHERE user_id=?",owner.id());
        assertThat(row.get("status")).isEqualTo("FAILED");assertThat(row.get("error_code")).isEqualTo("DIGEST_AI_UNCONFIRMED");assertThat(row.get("result_json")).isNull();assertThat(row.get("lease_token")).isNull();
        assertThat(digests.renew(lease)).isFalse();assertThat(digests.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_digest_sources WHERE user_id=?",Integer.class,owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragments WHERE user_id=?",Integer.class,owner.id())).isEqualTo(1);
    }
    @Test void deletingDuringMediaLeaseRetainsOriginalBucketAndWaitsForActiveLease() {
        var owner=owner();var item=source(owner);
        jdbc.update("UPDATE ingestion_jobs SET stage='MEDIA_SAVING',media_attempt_count=1,lease_owner=?,lease_expires_at=TIMESTAMPADD(MINUTE,20,UTC_TIMESTAMP(3)) WHERE id=?",UUID.randomUUID().toString(),item.jobId());
        assertThat(delete(owner,item.fragmentId()).getBody().path("cleanupPending").asBoolean()).isTrue();
        var row=jdbc.queryForMap("SELECT buckets,next_scan_at FROM fragment_deletions WHERE fragment_id=?",item.fragmentId());
        assertThat(row.get("buckets").toString()).contains("fixture-private");
        assertThat(jdbc.queryForObject("SELECT next_scan_at FROM fragment_deletions WHERE fragment_id=?",LocalDateTime.class,item.fragmentId()).toInstant(ZoneOffset.UTC)).isAfter(Instant.now().plusSeconds(20*60));
    }
    @Test void requiresAuthenticationHidesForeignTombstonesAndConfirmsEmptyDeletion() {
        var owner=owner();var stranger=owner();var item=source(owner);
        assertThat(http.exchange("/api/v1/fragments/"+item.fragmentId(),HttpMethod.DELETE,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(delete(stranger,item.fragmentId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(owner,item.fragmentId()).getBody().path("cleanupPending").asBoolean()).isFalse();
        assertThat(delete(stranger,item.fragmentId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void concurrentDeletesCreateOneCleanupIdentity() throws Exception {
        var owner=owner();var item=source(owner);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return deletions.delete(owner.id(),item.fragmentId());});var b=pool.submit(()->{gate.await();return deletions.delete(owner.id(),item.fragmentId());});gate.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)).stream().filter(DeletionResponse::duplicate)).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_jobs WHERE id=?",Integer.class,item.jobId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_deletions WHERE fragment_id=?",Integer.class,item.fragmentId())).isEqualTo(1);
    }
    @Test void lateParserResultCannotRecreateDeletedContent() {
        var owner=owner();var item=source(owner);var lease=parser.claim().orElseThrow();
        assertThat(lease.fragmentId()).isEqualTo(item.fragmentId());delete(owner,item.fragmentId());
        assertThat(parser.complete(lease,new com.fragpicker.integration.parsevideo.ParsedVideo("晚到视频",java.net.URI.create("https://example.com/video.mp4"),null,null,null,null))).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_video_metadata WHERE fragment_id=?",Integer.class,item.fragmentId())).isZero();
    }
}
