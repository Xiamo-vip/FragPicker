package com.fragpicker.digest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fragpicker.ingestion.*;
import com.fragpicker.knowledge.EnrichmentResult.Category;
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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+")
class DigestReadIntegrationTest {
    private static final LocalDate DAY=LocalDate.of(2026,10,5);
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubmissionService submissions;
    @Autowired DigestStore store;
    @Autowired com.fragpicker.knowledge.tool.HistoryToolFactory tools;
    private final List<Long> owners=new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",() -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",() -> System.getenv("DB_TEST_PASSWORD"));
    }
    @AfterEach void clean() { owners.forEach(owner -> jdbc.update("DELETE FROM users WHERE id=?",owner)); }
    private Account login() {
        var credentials=Map.of("username","read_"+UUID.randomUUID().toString().substring(0,8),"password","Integration-123!");
        var registered=http.postForEntity("/api/v1/auth/register",credentials,JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long owner=registered.getBody().path("id").asLong(); owners.add(owner);
        var login=http.postForEntity("/api/v1/auth/login",credentials,JsonNode.class); assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Account(owner,login.getBody().path("accessToken").asText());
    }
    private long seed(Account owner,String state) {
        long id=submissions.submit(owner.id(),UUID.randomUUID().toString(),new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(),null)).fragmentId();
        jdbc.update("UPDATE fragments SET business_date=?,status=? WHERE id=?",DAY,state,id);
        if ("READY".equals(state)) jdbc.update("INSERT INTO fragment_knowledge(fragment_id,user_id,duration_ms,summary,keywords,completed_at,enriched_summary,bullet_points,categories,enriched_at,enrichment_model) VALUES (?,?,3000,'原摘要',JSON_ARRAY('导数'),UTC_TIMESTAMP(3),'导数表示瞬时变化率',JSON_ARRAY('切线斜率'),JSON_ARRAY('LEARNING'),UTC_TIMESTAMP(3),'test')",id,owner.id());
        return id;
    }
    private void complete(Account owner,long source) {
        store.enqueue(owner.id(),DAY,LocalDateTime.of(2020,1,1,0,0),false); var lease=store.claim().orElseThrow(); store.snapshot(lease);
        assertThat(store.complete(lease,new DigestPiece(owner.id(),DAY,1,1,"导数课程回顾",List.of(new DigestPoint("瞬时变化率",List.of(source))),List.of(Category.LEARNING),List.of("导数")))).isTrue();
    }
    private HttpHeaders headers(Account owner) { var headers=new HttpHeaders(); headers.setBearerAuth(owner.token()); return headers; }
    private ResponseEntity<JsonNode> get(Account owner,String date) { return http.exchange("/api/v1/daily-digests/"+date,HttpMethod.GET,new HttpEntity<>(headers(owner)),JsonNode.class); }
    @Test void authenticationAndStrictDateAreRequired() {
        assertThat(http.getForEntity("/api/v1/daily-digests/2026-10-05",JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var owner=login(); for (String bad:List.of("0999-01-01","2026-02-29","2026-1-01","2026-13-01","nonsense")) {
            var result=get(owner,bad); assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); assertThat(result.getBody().path("code").asText()).isEqualTo("INVALID_DIGEST_DATE");
        }
    }
    @Test void emptyAndWaitingDaysContainOnlyOwnedCounts() {
        var owner=login(); var foreign=login(); seed(foreign,"READY");
        var empty=get(owner,DAY.toString()); assertThat(empty.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(empty.getBody().path("status").asText()).isEqualTo("EMPTY"); assertThat(empty.getBody().path("total").asLong()).isZero();
        seed(owner,"READY"); seed(owner,"QUEUED"); seed(owner,"FAILED"); var waiting=get(owner,DAY.toString()).getBody();
        assertThat(waiting.path("status").asText()).isEqualTo("WAITING"); assertThat(waiting.path("total").asLong()).isEqualTo(3);
        for (String field:List.of("ready","processing","failed")) assertThat(waiting.path(field).asLong()).isEqualTo(1);
        assertThat(waiting.path("result").isNull()).isTrue();
    }
    @Test void completedResultReturnsValidatedSourceCardsAndUtcGenerationTime() {
        var owner=login(); var foreign=login(); long source=seed(owner,"READY"); seed(foreign,"READY"); complete(owner,source);
        var response=get(owner,DAY.toString()); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); var body=response.getBody();
        assertThat(body.path("status").asText()).isEqualTo("READY"); assertThat(body.path("completedRevision").asLong()).isEqualTo(1);
        assertThat(Instant.parse(body.path("generatedAt").asText())).isNotNull();
        assertThat(body.path("result").path("sources")).hasSize(1); assertThat(body.path("result").path("sources").get(0).path("fragmentId").asLong()).isEqualTo(source);
        assertThat(body.path("result").path("summary").asText()).isEqualTo("导数课程回顾");
        assertThat(body.toString()).doesNotContain("userId","objectKey","lease_token","inFlightHash","modelCalls");
        assertThat(get(foreign,DAY.toString()).getBody().path("result").isNull()).isTrue();
    }
    @Test void rebuildingOrFailedRevisionRetainsThePreviousResultAndMarksItOutdated() {
        var owner=login(); long source=seed(owner,"READY"); complete(owner,source);
        store.enqueue(owner.id(),DAY,LocalDateTime.of(2020,1,1,0,0),true); var queued=get(owner,DAY.toString()).getBody();
        assertThat(queued.path("status").asText()).isEqualTo("QUEUED"); assertThat(queued.path("outdated").asBoolean()).isTrue(); assertThat(queued.path("result").isNull()).isFalse();
        var lease=store.claim().orElseThrow(); store.fail(lease,"DIGEST_AI_UNCONFIRMED"); var failed=get(owner,DAY.toString()).getBody();
        assertThat(failed.path("status").asText()).isEqualTo("FAILED"); assertThat(failed.path("errorCode").asText()).isEqualTo("DIGEST_AI_UNCONFIRMED");
        assertThat(failed.path("result")).isEqualTo(queued.path("result"));
    }
    @Test void removedSourceHidesOldSummaryUntilRebuilt() {
        var owner=login(); long source=seed(owner,"READY"); complete(owner,source); jdbc.update("DELETE FROM fragments WHERE id=?",source);
        var stale=get(owner,DAY.toString()).getBody(); assertThat(stale.path("status").asText()).isEqualTo("STALE");
        assertThat(stale.path("result").isNull()).isTrue(); assertThat(stale.path("errorCode").asText()).isEqualTo("DIGEST_SOURCE_CHANGED");
    }
    @Test void boundToolReadsOnlyOwnedDayAndReportsAnotherAccountsDayAsEmpty() throws Exception {
        var owner=login(); var foreign=login(); long source=seed(owner,"READY"); complete(owner,source);
        var request=dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("daily").name("getDailyDigest").arguments("{\"date\":\"2026-10-05\"}").build();
        var mine=tools.bind(new com.fragpicker.auth.CurrentUser(owner.id())).execute(request);
        assertThat(mine).contains("导数课程回顾","READY").doesNotContain("fragmentId","userId","lease");
        var other=tools.bind(new com.fragpicker.auth.CurrentUser(foreign.id())).execute(request);
        assertThat(other).contains("EMPTY").doesNotContain("导数课程回顾");
    }
    private record Account(long id,String token) { @Override public String toString() { return "Account[REDACTED]"; } }
}
