package com.fragpicker.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.auth.*;
import com.fragpicker.ingestion.*;
import com.fragpicker.integration.chat.ChatModelProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+")
@EnabledIfEnvironmentVariable(named="DIGEST_WORKER_TEST_ENABLED",matches="true")
class DigestWorkerLiveIntegrationTest {
    private static final LocalDate DAY=LocalDate.of(2026,10,6);
    @Autowired DigestStore store; @Autowired DigestMapper jobs; @Autowired DailyDigestGenerator generator;
    @Autowired ChatModel model; @Autowired ChatModelProperties provider; @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc; @Autowired RegistrationService registrations; @Autowired SubmissionService submissions;
    private final List<Long> owners=new ArrayList<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",() -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",() -> System.getenv("DB_TEST_PASSWORD"));
        registry.add("fragpicker.integrations.chat.enabled",() -> true);
        registry.add("fragpicker.integrations.chat.timeout",() -> "90s");
    }
    @AfterEach void clean() { owners.forEach(owner -> jdbc.update("DELETE FROM users WHERE id=?",owner)); }
    private long owner() { long id=registrations.register(new RegisterRequest("live_"+UUID.randomUUID().toString().substring(0,8),"Integration-123!")).id(); owners.add(id); return id; }
    private void seed(long owner,int count) {
        for (int i=0;i<count;i++) {
            long id=submissions.submit(owner,UUID.randomUUID().toString(),new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(),null)).fragmentId();
            jdbc.update("UPDATE fragments SET business_date=?,status='READY' WHERE id=?",DAY,id);
            jdbc.update("INSERT INTO fragment_knowledge(fragment_id,user_id,duration_ms,summary,keywords,completed_at,enriched_summary,bullet_points,categories,enriched_at,enrichment_model) VALUES (?,?,3000,'导数课程',JSON_ARRAY('导数','变化率'),UTC_TIMESTAMP(3),'导数表示函数瞬时变化率，也对应曲线在一点的切线斜率；通过极限定义理解，再用幂函数求导公式练习。',JSON_ARRAY('导数衡量瞬时变化率','切线斜率帮助理解导数'),JSON_ARRAY('LEARNING'),UTC_TIMESTAMP(3),'test-fixture')",id,owner);
        }
    }
    @Test void realDeepSeekPersistsOwnedBatchesAndRebuildReusesAllCheckpoints() throws Exception {
        long owner=owner(),foreign=owner(); seed(owner,9); seed(foreign,1);
        var count=new AtomicInteger();
        ChatModel monitored=new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                count.incrementAndGet(); assertThat(jobs.day(owner,DAY).inFlightHash()).hasSize(64); return model.chat(request);
            }
        };
        var worker=new DigestWorker(store,generator,monitored,provider,json);
        store.enqueue(owner,DAY,LocalDateTime.of(2026,1,1,0,0),false);
        assertThat(worker.processNext()).isTrue(); var first=jobs.day(owner,DAY);
        assertThat(first.status()).isEqualTo("READY"); assertThat(first.sourceCount()).isEqualTo(9); assertThat(count).hasValue(3);
        assertThat(first.modelCalls()).isEqualTo(3); assertThat(first.inFlightHash()).isNull();
        var piece=json.readValue(first.resultJson(),DigestPiece.class); assertThat(piece.summary()).contains("导数");
        for (var point:piece.points()) for (long id:point.sourceIds()) assertThat(jdbc.queryForObject("SELECT user_id FROM fragments WHERE id=?",Long.class,id)).isEqualTo(owner);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_digest_checkpoints WHERE user_id=?",Integer.class,owner)).isEqualTo(3);
        store.enqueue(owner,DAY,LocalDateTime.of(2026,1,1,0,0),true); assertThat(worker.processNext()).isTrue();
        var rebuilt=jobs.day(owner,DAY); assertThat(rebuilt.status()).isEqualTo("READY"); assertThat(rebuilt.completedRevision()).isEqualTo(2);
        assertThat(rebuilt.resultJson()).isEqualTo(first.resultJson()); assertThat(count).hasValue(3);
        System.out.println("Real DeepSeek/MySQL digest worker: three committed checkpoints, owned result, and zero additional calls for unchanged rebuild.");
    }
}
