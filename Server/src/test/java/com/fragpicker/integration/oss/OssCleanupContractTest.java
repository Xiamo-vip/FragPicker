package com.fragpicker.integration.oss;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OssCleanupContractTest {
    HttpServer fixture;OSS client;OssMediaStorage storage;
    String prefix="users/10/fragments/20/",key=prefix+"video/"+"a".repeat(64);
    boolean empty=false,partial=false;int status=200;
    String versioning="",version="old-version";boolean versioned=false;
    String deleteBody,query;AtomicInteger reads=new AtomicInteger(),deletes=new AtomicInteger();
    @BeforeEach void fixture() throws Exception {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("com.aliyun.oss")).setLevel(ch.qos.logback.classic.Level.OFF);
        fixture=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        fixture.createContext("/",exchange->{
            String response;
            String rawQuery=exchange.getRequestURI().getRawQuery();
            if(exchange.getRequestMethod().equals("GET") && rawQuery.startsWith("versioning")) {
                response="<VersioningConfiguration>"+(versioning.isEmpty()?"":"<Status>"+versioning+"</Status>")+"</VersioningConfiguration>";
            } else if(exchange.getRequestMethod().equals("GET")) {
                reads.incrementAndGet();query=URLDecoder.decode(exchange.getRequestURI().getRawQuery(),java.nio.charset.StandardCharsets.UTF_8);
                if(versioned)response="<ListVersionsResult><Name>fixture-private</Name><Prefix>"+prefix+"</Prefix><MaxKeys>100</MaxKeys><IsTruncated>false</IsTruncated>"+
                    (empty?"":"<Version><Key>"+key+"</Key><VersionId>"+version+"</VersionId><IsLatest>false</IsLatest><LastModified>2026-10-07T00:00:00.000Z</LastModified><ETag>fixture</ETag><Size>3</Size><StorageClass>Standard</StorageClass><Owner><ID>fixture-owner</ID><DisplayName>fixture</DisplayName></Owner></Version>"+
                    "<DeleteMarker><Key>"+key+"</Key><VersionId>marker-version</VersionId><IsLatest>true</IsLatest><LastModified>2026-10-07T00:00:00.000Z</LastModified><Owner><ID>fixture-owner</ID><DisplayName>fixture</DisplayName></Owner></DeleteMarker>")+"</ListVersionsResult>";
                else response="<ListBucketResult><Name>fixture-private</Name><Prefix>"+prefix+"</Prefix><MaxKeys>100</MaxKeys><KeyCount>"+(empty?0:1)+"</KeyCount><IsTruncated>false</IsTruncated>"+
                    (empty?"":"<Contents><Key>"+key+"</Key><LastModified>2026-10-07T00:00:00.000Z</LastModified><ETag>fixture</ETag><Size>3</Size><StorageClass>Standard</StorageClass></Contents>")+"</ListBucketResult>";
            } else {
                deletes.incrementAndGet();deleteBody=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                response="<DeleteResult>"+(partial?"":versioned?"<Deleted><Key>"+key+"</Key><VersionId>"+version+"</VersionId></Deleted><Deleted><Key>"+key+"</Key><VersionId>marker-version</VersionId></Deleted>":"<Deleted><Key>"+key+"</Key></Deleted>")+"</DeleteResult>";
            }
            if(status!=200)response="<Error><Code>AccessDenied</Code><Message>provider-secret</Message><RequestId>fixture</RequestId></Error>";
            byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/xml");exchange.sendResponseHeaders(status,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });fixture.start();
        var conf=new ClientBuilderConfiguration();conf.setSignatureVersion(SignVersion.V4);conf.setSLDEnabled(true);conf.setMaxErrorRetry(0);conf.setConnectionTimeout(2000);conf.setSocketTimeout(2000);conf.setCrcCheckEnabled(false);
        client=OSSClientBuilder.create().endpoint("http://127.0.0.1:"+fixture.getAddress().getPort()).region("cn-shenzhen").credentialsProvider(new DefaultCredentialProvider("fixture-ak","fixture-secret")).clientConfiguration(conf).build();
        storage=new OssMediaStorage(client,new OssProperties(true,"fixture-private","oss-cn-shenzhen.aliyuncs.com",1048576,1048576,Duration.ofMinutes(5)),Clock.systemUTC());
    }
    @AfterEach void close(){client.shutdown();fixture.stop(0);}
    @Test void listsBoundedOwnedPrefixAndConfirmsEmptyOnSeparatePass() {
        assertThat(storage.deleteOwnedPage("fixture-private",10,20,()->true)).isFalse();
        assertThat(query).contains("list-type=2","max-keys=100","prefix="+prefix);assertThat(deleteBody).contains("<Key>"+key+"</Key>");
        empty=true;assertThat(storage.deleteOwnedPage("fixture-private",10,20,()->true)).isTrue();assertThat(reads).hasValue(2);assertThat(deletes).hasValue(1);
    }
    @Test void rejectsMalformedOrForeignKeysBeforeBulkDelete() {
        key="users/11/fragments/20/video/"+"b".repeat(64);
        assertThatThrownBy(()->storage.deleteOwnedPage("fixture-private",10,20,()->true)).isInstanceOf(OssStorageFailure.class);assertThat(deletes).hasValue(0);
        key=prefix+"../escape";
        assertThatThrownBy(()->storage.deleteOwnedPage("fixture-private",10,20,()->true)).isInstanceOf(OssStorageFailure.class);assertThat(deletes).hasValue(0);
    }
    @Test void lostLeaseBeforeDeletePreservesObjects() {
        var checks=new AtomicInteger();assertThat(storage.deleteOwnedPage("fixture-private",10,20,()->checks.incrementAndGet()<=2)).isFalse();assertThat(deletes).hasValue(0);
        int previous=reads.get();assertThat(storage.deleteOwnedPage("fixture-private",10,20,()->false)).isFalse();assertThat(reads).hasValue(previous);
    }
    @Test void partialAcknowledgementIsRetryableAndProviderMessagesAreNotExposed() {
        partial=true;assertThatThrownBy(()->storage.deleteOwnedPage("fixture-private",10,20,()->true)).isInstanceOfSatisfying(OssStorageFailure.class,error->assertThat(error.retryable()).isTrue());
        status=403;assertThatThrownBy(()->storage.deleteOwnedPage("fixture-private",10,20,()->true)).isInstanceOfSatisfying(OssStorageFailure.class,error->{assertThat(error.code()).isEqualTo(OssStorageFailure.Code.ACCESS_DENIED);assertThat(error.getMessage()).doesNotContain("provider-secret");});
    }
    @Test void enabledAndSuspendedBucketsDeleteExplicitOldVersionsAndMarkers() {
        for(String state:java.util.List.of("Enabled","Suspended")) {
            versioning=state;versioned=true;empty=false;
            assertThat(storage.deleteOwnedPage("fixture-private",10,20,()->true)).isFalse();
            assertThat(query).contains("versions","prefix="+prefix,"max-keys=100");assertThat(deleteBody).contains("<VersionId>old-version</VersionId>","<VersionId>marker-version</VersionId>");
            empty=true;assertThat(storage.deleteOwnedPage("fixture-private",10,20,()->true)).isTrue();
        }
    }
    @Test void versionPageIsFullyValidatedAndPartialVersionResultRequiresAnotherPass() {
        versioning="Enabled";versioned=true;version=" ";
        assertThatThrownBy(()->storage.deleteOwnedPage("fixture-private",10,20,()->true)).isInstanceOf(OssStorageFailure.class);assertThat(deletes).hasValue(0);
        version="old-version";partial=true;assertThatThrownBy(()->storage.deleteOwnedPage("fixture-private",10,20,()->true)).isInstanceOfSatisfying(OssStorageFailure.class,error->assertThat(error.retryable()).isTrue());
    }
}
