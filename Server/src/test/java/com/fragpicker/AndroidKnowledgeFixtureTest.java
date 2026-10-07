package com.fragpicker;

import com.fragpicker.auth.*;
import com.fragpicker.ingestion.*;
import com.fragpicker.knowledge.KnowledgeResultMapper;
import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import com.fragpicker.knowledge.index.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in: retained fixtures only in the disposable database owned by Test-MySql.ps1. */
@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+/fragpicker_test\\?.+")
@EnabledIfEnvironmentVariable(named = "ANDROID_KNOWLEDGE_FIXTURE", matches = "true")
class AndroidKnowledgeFixtureTest {
    @Autowired RegistrationService registrations;
    @Autowired SubmissionService submissions;
    @Autowired KnowledgeResultMapper knowledge;
    @Autowired IndexStore indexes;
    @Autowired @Lazy LocalEmbeddingService embeddings;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fragpicker.digest.DigestStore digests;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }
    @Test void preparesOwnedAndForeignIndexedTeachingSourcesForDeviceVerification() {
        long owner = registrations.register(new RegisterRequest("android_fixture", "Android-fixture-123!")).id();
        long foreign = registrations.register(new RegisterRequest("android_foreign", "Android-fixture-123!")).id();
        long fragment = source(owner, "导数与瞬时变化率课程"); long foreignFragment=source(foreign, "其他人的秘密数学课程");
        var worker = new IndexWorker(indexes, embeddings);
        assertThat(worker.runOnce()).isTrue(); assertThat(worker.runOnce()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM fragments WHERE id = ?", String.class, fragment)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragments WHERE status = 'READY'", Integer.class)).isEqualTo(2);
        digest(owner,fragment,"今天学习了导数与瞬时变化率，先理解极限，再练习求导。");
        digest(foreign,foreignFragment,"其他人的秘密每日总结");
    }
    private void digest(long owner,long fragment,String summary) {
        var day=jdbc.queryForObject("SELECT business_date FROM fragments WHERE id=?",java.time.LocalDate.class,fragment);
        digests.enqueue(owner,day,java.time.LocalDateTime.of(2000,1,1,0,0),false);
        var lease=digests.claim().orElseThrow(); digests.snapshot(lease);
        assertThat(digests.complete(lease,new com.fragpicker.digest.DigestPiece(owner,day,1,1,summary,
            List.of(new com.fragpicker.digest.DigestPoint("通过切线斜率理解导数",List.of(fragment))),
            List.of(com.fragpicker.knowledge.EnrichmentResult.Category.LEARNING),List.of("导数","变化率")))).isTrue();
        jdbc.update("UPDATE daily_digest_changes SET scheduled_version=version WHERE user_id=? AND business_date=?",owner,day);
    }
    private long source(long owner, String title) {
        String summary = "导数描述函数的瞬时变化率。用差商极限求导，可以计算曲线切线斜率，建议先学习极限再练习求导。";
        var item = submissions.submit(owner, UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID(), "设备来源卡片测试"));
        knowledge.insert(item.fragmentId(), owner, 3000, summary, "[]");
        jdbc.update("UPDATE fragment_knowledge SET enriched_summary = ?, bullet_points = JSON_ARRAY(?), categories = JSON_ARRAY('LEARNING'), enrichment_model = 'fixture', enriched_at = UTC_TIMESTAMP(3) WHERE fragment_id = ?", summary, summary, item.fragmentId());
        jdbc.update("INSERT INTO fragment_sentences (fragment_id,user_id,ordinal,paragraph_id,speaker_id,sentence_id,start_ms,end_ms,content) VALUES (?,?,0,0,'1',0,0,3000,?)", item.fragmentId(), owner, summary);
        jdbc.update("UPDATE ingestion_jobs SET stage = 'INDEX_PENDING' WHERE id = ?", item.jobId());
        jdbc.update("UPDATE fragments SET status = 'INDEX_PENDING' WHERE id = ?", item.fragmentId());
        jdbc.update("INSERT INTO fragment_video_metadata (fragment_id,user_id,title,video_url,parsed_at) VALUES (?,?,?,'https://example.com/test-only.mp4',UTC_TIMESTAMP(3))", item.fragmentId(), owner, title);
        for (String kind : List.of("VIDEO", "COVER")) jdbc.update("INSERT INTO fragment_stored_media (fragment_id,user_id,kind,bucket,object_key,size_bytes,sha256,content_type,stored_at) VALUES (?,?,?,'test-private',?,10,REPEAT('a',64),?,UTC_TIMESTAMP(3))", item.fragmentId(), owner, kind, "android-fixture/" + kind, kind.equals("VIDEO") ? "video/mp4" : "image/jpeg");
        return item.fragmentId();
    }
}
