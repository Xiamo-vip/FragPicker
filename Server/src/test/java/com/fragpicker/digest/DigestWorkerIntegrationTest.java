package com.fragpicker.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.auth.*;
import com.fragpicker.ingestion.*;
import com.fragpicker.integration.chat.ChatModelProperties;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+")
class DigestWorkerIntegrationTest {
    private static final LocalDate DAY=LocalDate.of(2026,10,6);
    private static final ChatModelProperties PROVIDER=new ChatModelProperties(true,"https://test.invalid","test-only","test-model",Duration.ofSeconds(60),4096);
    @Autowired DigestStore store;
    @Autowired DigestMapper jobs;
    @Autowired DailyDigestGenerator generator;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource database;
    @Autowired RegistrationService registrations;
    @Autowired SubmissionService submissions;
    private final List<Long> owners=new ArrayList<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username",() -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password",() -> System.getenv("DB_TEST_PASSWORD"));
    }
    @AfterEach void clean() { owners.forEach(owner -> jdbc.update("DELETE FROM users WHERE id=?",owner)); }
    private long owner() { long id=registrations.register(new RegisterRequest("worker_"+UUID.randomUUID().toString().substring(0,8),"Integration-123!")).id(); owners.add(id); return id; }
    private void seed(long owner,int count) {
        for (int i=0;i<count;i++) {
            long id=submissions.submit(owner,UUID.randomUUID().toString(),new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(),null)).fragmentId();
            jdbc.update("UPDATE fragments SET business_date=?,status='READY' WHERE id=?",DAY,id);
            jdbc.update("INSERT INTO fragment_knowledge(fragment_id,user_id,duration_ms,summary,keywords,completed_at,enriched_summary,bullet_points,categories,enriched_at,enrichment_model) VALUES (?,?,3000,'原摘要',JSON_ARRAY('导数'),UTC_TIMESTAMP(3),'导数表示瞬时变化率',JSON_ARRAY('切线斜率帮助理解导数'),JSON_ARRAY('LEARNING'),UTC_TIMESTAMP(3),'test')",id,owner);
        }
    }
    private DigestWorker worker(ChatModel model) { return new DigestWorker(store,generator,model,PROVIDER,json); }
    private ChatResponse answer(ChatRequest request) throws Exception {
        var input=json.readTree(((UserMessage)request.messages().get(1)).singleText());
        var body=Map.of("summary","导数学习回顾","points",List.of(Map.of("text","导数表示瞬时变化率","sourceIds",List.of(input.path("allowedSourceIds").get(0).asLong()))),"categories",List.of("LEARNING"),"keywords",List.of("导数"));
        return ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(body))).build();
    }
    private void enqueue(long owner,boolean rebuild) { store.enqueue(owner,DAY,LocalDateTime.of(2026,1,1,0,0),rebuild); }
    private void expire(DigestLease lease) { jdbc.update("UPDATE daily_digests SET lease_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",lease.id()); }
    private long checkpoints(long owner) { return jdbc.queryForObject("SELECT COUNT(*) FROM daily_digest_checkpoints WHERE user_id=?",Long.class,owner); }
    @Test void allBatchesCommitBeforeEachExternalCallAndResultIsSaved() throws Exception {
        long owner=owner(),foreign=owner(); seed(owner,9); seed(foreign,1); enqueue(owner,false);
        var model=mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            try (var connection=database.getConnection(); var statement=connection.prepareStatement("SELECT in_flight_hash,working_source_count FROM daily_digests WHERE user_id=?")) {
                statement.setLong(1,owner); try (var row=statement.executeQuery()) { assertThat(row.next()).isTrue(); assertThat(row.getString(1)).hasSize(64); assertThat(row.getLong(2)).isEqualTo(9); }
            }
            return answer(call.getArgument(0));
        });
        assertThat(worker(model).processNext()).isTrue(); verify(model,times(3)).chat(any(ChatRequest.class));
        var saved=jobs.day(owner,DAY); assertThat(saved.status()).isEqualTo("READY"); assertThat(saved.sourceCount()).isEqualTo(9);
        assertThat(saved.modelCalls()).isEqualTo(3); assertThat(saved.inFlightHash()).isNull(); assertThat(checkpoints(owner)).isEqualTo(3);
        var piece=json.readValue(saved.resultJson(),DigestPiece.class);
        for (var point:piece.points()) for (long id:point.sourceIds()) assertThat(jdbc.queryForObject("SELECT user_id FROM fragments WHERE id=?",Long.class,id)).isEqualTo(owner);
        assertThat(worker(model).processNext()).isFalse(); verifyNoMoreInteractions(model);
    }
    @Test void resumesSavedLeafAfterLeaseExpiryWithoutRepeatingThatCall() throws Exception {
        long owner=owner(); seed(owner,9); enqueue(owner,false); var old=store.claim().orElseThrow(); store.snapshot(old);
        var model=mock(ChatModel.class); when(model.chat(any(ChatRequest.class))).thenAnswer(call -> answer(call.getArgument(0)));
        var hash=new AtomicReference<String>();
        generator.generate(owner,DAY,store.sources(old,null).subList(0,8).iterator(),() -> false,
            piece -> assertThat(store.checkpoint(old,hash.get(),piece)).isTrue(),request -> {
                hash.set(DigestRequestFingerprint.hash(request,PROVIDER,json)); assertThat(store.beginCall(old,hash.get())).isTrue(); return model.chat(request);
            });
        assertThat(checkpoints(owner)).isEqualTo(1); expire(old);
        assertThat(worker(model).processNext()).isTrue(); verify(model,times(3)).chat(any(ChatRequest.class));
        assertThat(jobs.day(owner,DAY).status()).isEqualTo("READY"); assertThat(checkpoints(owner)).isEqualTo(3);
    }
    @Test void failureDoesNotAutomaticallyRetryAndExplicitRebuildReusesSuccessfulLeaf() throws Exception {
        long owner=owner(); seed(owner,9); enqueue(owner,false); var model=mock(ChatModel.class); var calls=new AtomicInteger();
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> { if (calls.incrementAndGet()==2) throw new IllegalStateException("private-provider-body"); return answer(call.getArgument(0)); });
        var worker=worker(model); assertThat(worker.processNext()).isTrue(); assertThat(jobs.day(owner,DAY).status()).isEqualTo("FAILED");
        assertThat(checkpoints(owner)).isEqualTo(1); assertThat(jobs.day(owner,DAY).errorCode()).isEqualTo("DIGEST_AI_UNAVAILABLE");
        assertThat(worker.processNext()).isFalse(); assertThat(calls).hasValue(2);
        enqueue(owner,true); assertThat(worker.processNext()).isTrue(); assertThat(calls).hasValue(4);
        assertThat(jobs.day(owner,DAY).status()).isEqualTo("READY"); assertThat(jobs.day(owner,DAY).completedRevision()).isEqualTo(2);
    }
    @Test void staleProviderResponseCannotReplaceAnUnconfirmedFailure() throws Exception {
        long owner=owner(); seed(owner,1); enqueue(owner,false); var model=mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            jdbc.update("UPDATE daily_digests SET lease_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE user_id=?",owner);
            assertThat(store.claim()).isEmpty(); return answer(call.getArgument(0));
        });
        assertThat(worker(model).processNext()).isTrue(); var saved=jobs.day(owner,DAY);
        assertThat(saved.status()).isEqualTo("FAILED"); assertThat(saved.errorCode()).isEqualTo("DIGEST_AI_UNCONFIRMED");
        assertThat(saved.completedRevision()).isZero(); assertThat(saved.resultJson()).isNull(); assertThat(checkpoints(owner)).isZero();
    }
    @Test void emptyDayPublishesWithoutAnyModelInteraction() {
        long owner=owner(); enqueue(owner,false); var model=mock(ChatModel.class);
        assertThat(worker(model).processNext()).isTrue(); verifyNoInteractions(model);
        assertThat(jobs.day(owner,DAY).status()).isEqualTo("READY"); assertThat(jobs.day(owner,DAY).sourceCount()).isZero();
    }
}
