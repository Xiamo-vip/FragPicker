package com.fragpicker.ingestion;

import com.aliyun.oss.*;
import com.aliyun.oss.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.auth.*;
import com.fragpicker.ingestion.deletion.*;
import com.fragpicker.integration.oss.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="DB_TEST_URL",matches=".+")
class CleanupWorkerIntegrationTest {
    @Autowired CleanupStore store;
    @Autowired RegistrationService registrations;
    @Autowired SubmissionService submissions;
    @Autowired DeletionService deletions;
    @Autowired MediaJobStore media;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    final List<Long> owners=new ArrayList<>();OSS sdk;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("DB_TEST_URL"));registry.add("spring.datasource.username",()->System.getenv("DB_TEST_USERNAME"));registry.add("spring.datasource.password",()->System.getenv("DB_TEST_PASSWORD"));
        registry.add("fragpicker.integrations.oss.bucket",()->"fixture-private");
    }
    @BeforeEach void sdk(){sdk=mock(OSS.class);when(sdk.getBucketVersioning(anyString())).thenReturn(new BucketVersioningConfiguration(BucketVersioningConfiguration.OFF));when(sdk.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call->page(call.getArgument(0),false));when(sdk.deleteObjects(any(DeleteObjectsRequest.class))).thenAnswer(call->new DeleteObjectsResult(((DeleteObjectsRequest)call.getArgument(0)).getKeys()));}
    record Item(long owner,long fragment,long job) { }
    Item item(boolean active) {
        long owner=registrations.register(new RegisterRequest("cleanup_"+UUID.randomUUID().toString().substring(0,8),"Integration-123!")).id();owners.add(owner);
        var saved=submissions.submit(owner,UUID.randomUUID().toString(),new SubmissionRequest("https://b23.tv/"+UUID.randomUUID(),null));
        if(active) {
            jdbc.update("UPDATE ingestion_jobs SET stage='MEDIA_PENDING' WHERE id=?",saved.jobId());
            jdbc.update("INSERT INTO fragment_video_metadata(fragment_id,user_id,video_url,parsed_at) VALUES (?,?,'https://example.com/video',UTC_TIMESTAMP(3))",saved.fragmentId(),owner);
        } else jdbc.update("INSERT INTO fragment_stored_media(fragment_id,user_id,kind,bucket,object_key,size_bytes,sha256,content_type,stored_at) VALUES (?,?,'VIDEO','fixture-private',?,3,REPEAT('a',64),'video/mp4',UTC_TIMESTAMP(3))",saved.fragmentId(),owner,"users/"+owner+"/fragments/"+saved.fragmentId()+"/video/"+"a".repeat(64));
        return new Item(owner,saved.fragmentId(),saved.jobId());
    }
    ListObjectsV2Result page(ListObjectsV2Request request,boolean nonEmpty) {
        var page=new ListObjectsV2Result();page.setBucketName(request.getBucketName());page.setPrefix(request.getPrefix());page.setTruncated(false);
        if(nonEmpty){var object=new OSSObjectSummary();object.setKey(request.getPrefix()+"video/"+"a".repeat(64));page.addObjectSummary(object);}return page;
    }
    CleanupWorker worker(){return new CleanupWorker(store,new OssMediaStorage(sdk,new OssProperties(true,"fixture-private","oss-cn-shenzhen.aliyuncs.com",1048576,1048576,Duration.ofMinutes(5)),Clock.systemUTC()),json);}
    void due(Item item){jdbc.update("UPDATE fragment_deletions SET next_scan_at='2000-01-01' WHERE fragment_id=? AND user_id=?",item.fragment(),item.owner());}
    String status(Item item){return jdbc.queryForObject("SELECT status FROM fragment_deletions WHERE fragment_id=? AND user_id=?",String.class,item.fragment(),item.owner());}
    @AfterEach void clean(){for(long owner:owners){jdbc.update("DELETE FROM users WHERE id=?",owner);jdbc.update("DELETE FROM fragment_deletions WHERE user_id=?",owner);}}
    @Test void confirmsPrefixEmptyThenRechecksForLateObjectsWithoutRecreatingFragment() {
        var item=item(false);deletions.delete(item.owner(),item.fragment());
        when(sdk.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call->page(call.getArgument(0),true));
        assertThat(worker().runOnce()).isTrue();assertThat(status(item)).isEqualTo("QUEUED");verify(sdk,times(1)).deleteObjects(any());
        due(item);when(sdk.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call->page(call.getArgument(0),false));
        assertThat(worker().runOnce()).isTrue();assertThat(status(item)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT next_scan_at FROM fragment_deletions WHERE fragment_id=?",LocalDateTime.class,item.fragment()).toInstant(ZoneOffset.UTC)).isAfter(Instant.now().plusSeconds(86390));
        due(item);when(sdk.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call->page(call.getArgument(0),true));worker().runOnce();verify(sdk,times(2)).deleteObjects(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragments WHERE id=?",Integer.class,item.fragment())).isZero();
    }
    @Test void recoversExpiredLeaseAndRejectsWrongOwnerAndLateCompletion() {
        var item=item(false);deletions.delete(item.owner(),item.fragment());var old=store.claim().orElseThrow();
        jdbc.update("UPDATE fragment_deletions SET lease_expires_at='2000-01-01' WHERE fragment_id=?",item.fragment());var next=store.claim().orElseThrow();
        assertThat(store.finish(old,true)).isFalse();assertThat(store.renew(old)).isFalse();
        var wrong=new CleanupLease(next.userId()+1,next.fragmentId(),next.buckets(),next.version(),next.token(),next.attempt());
        assertThat(store.renew(wrong)).isFalse();assertThat(store.finish(wrong,true)).isFalse();assertThat(store.finish(next,true)).isTrue();
    }
    @Test void retainsFailureWithBackoffAndResumesAfterProviderRecovers() {
        var item=item(false);deletions.delete(item.owner(),item.fragment());when(sdk.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(new ClientException("fixture-no-details"));
        worker().runOnce();assertThat(status(item)).isEqualTo("FAILED");assertThat(jdbc.queryForObject("SELECT error_code FROM fragment_deletions WHERE fragment_id=?",String.class,item.fragment())).isEqualTo("OSS_UNAVAILABLE");
        assertThat(worker().runOnce()).isFalse();due(item);when(sdk.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call->page(call.getArgument(0),false));worker().runOnce();assertThat(status(item)).isEqualTo("CONFIRMED");
    }
    @Test void staleMediaUploadImmediatelyReopensCleanupForItsOwnedPrefix() {
        var item=item(true);var old=media.claim().orElseThrow();deletions.delete(item.owner(),item.fragment());due(item);worker().runOnce();assertThat(status(item)).isEqualTo("CONFIRMED");
        var uploaded=new StoredMedia("fixture-private","users/"+item.owner()+"/fragments/"+item.fragment()+"/video/"+"a".repeat(64),3,"a".repeat(64),"video/mp4");
        assertThat(media.checkpoint(old,MediaKind.VIDEO,uploaded)).isFalse();assertThat(status(item)).isEqualTo("QUEUED");assertThat(worker().runOnce()).isTrue();
    }
    @Test void concurrentClaimsSelectDeletedIdentityOnlyOnce() throws Exception {
        var item=item(false);deletions.delete(item.owner(),item.fragment());var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return store.claim();});var b=pool.submit(()->{gate.await();return store.claim();});gate.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)).stream().filter(Optional::isPresent)).hasSize(1);
        }
    }
}
