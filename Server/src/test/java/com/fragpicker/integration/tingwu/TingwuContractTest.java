package com.fragpicker.integration.tingwu;

import com.aliyun.teaopenapi.models.Config;
import com.aliyun.tingwu20230930.Client;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.fragpicker.integration.aliyun.AliyunCredentialsProperties;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class TingwuContractTest {
    private HttpServer fixture;
    private TingwuClient client;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = "{\"Code\":\"0\",\"Data\":{\"TaskId\":\"task123\",\"TaskKey\":\"fixture-key\",\"TaskStatus\":\"ONGOING\"}}";
    private volatile JsonNode request;
    private volatile String path, query, method, authorization;
    private volatile long delay;
    private final URI source = URI.create("https://fixture-bucket.oss-cn-shenzhen.aliyuncs.com/video?signature=test-only");

    static TingwuProperties properties() {
        return new TingwuProperties(true, "fixture-app", "auto", Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(2), 4096, Duration.ofHours(4));
    }
    @BeforeEach void start() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            calls.incrementAndGet(); method = exchange.getRequestMethod(); path = exchange.getRequestURI().getPath();
            query = exchange.getRequestURI().getQuery(); authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] input = exchange.getRequestBody().readAllBytes(); request = input.length == 0 ? null : json.readTree(input);
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] output = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, output.length);
                try (var stream = exchange.getResponseBody()) { stream.write(output); }
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException aborted) { /* timeout test */ }
            finally { exchange.close(); }
        });
        fixture.start();
        var sdk = new Client(new Config().setAccessKeyId("fixture-id").setAccessKeySecret("fixture-secret")
                .setRegionId("cn-beijing").setProtocol("HTTP").setEndpoint("127.0.0.1:" + fixture.getAddress().getPort())
                .setEnableUsageDataCollection(false));
        client = new TingwuClient(sdk, properties());
    }
    @AfterEach void stop() { fixture.stop(0); }

    @Test void signsOfflineCreationAndEnablesOnlyRequestedFeatures() {
        var created = client.create(source, "fixture-key");
        assertThat(created.status()).isEqualTo(TingwuTask.Status.ONGOING); assertThat(created.id()).isEqualTo("task123");
        assertThat(method).isEqualTo("PUT"); assertThat(path).isEqualTo("/openapi/tingwu/v2/tasks");
        assertThat(query).contains("type=offline"); assertThat(authorization).isNotBlank();
        assertThat(request.path("AppKey").asText()).isEqualTo("fixture-app");
        assertThat(request.path("Input").path("FileUrl").asText()).isEqualTo(source.toString());
        assertThat(request.path("Input").path("TaskKey").asText()).isEqualTo("fixture-key");
        assertThat(request.path("Input").path("SourceLanguage").asText()).isEqualTo("auto");
        assertThat(request.path("Parameters").path("Summarization").path("Types").get(0).asText()).isEqualTo("Paragraph");
        assertThat(request.path("Parameters").path("MeetingAssistance").path("Types").get(0).asText()).isEqualTo("KeyInformation");
        assertThat(request.path("Parameters").has("PptExtractionEnabled")).isFalse();
        assertThat(created.toString()).doesNotContain("signature").doesNotContain("task123");
    }
    @Test void mapsCompletionResultUrlsAndFailureWithoutProviderMessages() {
        body = """
                {"Code":0,"Data":{"TaskId":"task123","TaskStatus":"COMPLETED","Result":{
                  "Transcription":"http://output-bucket.oss-cn-zhangjiakou.aliyuncs.com/asr.json?sign=temporary",
                  "Summarization":"https://output-bucket.oss-cn-zhangjiakou.aliyuncs.com/summary.json"}}}
                """;
        var complete = client.get("task123");
        assertThat(complete.status()).isEqualTo(TingwuTask.Status.COMPLETED);
        assertThat(complete.transcriptionUrl().getScheme()).isEqualTo("https");
        assertThat(complete.transcriptionUrl().getRawQuery()).isEqualTo("sign=temporary");
        assertThat(path).isEqualTo("/openapi/tingwu/v2/tasks/task123"); assertThat(method).isEqualTo("GET");
        body = "{\"Code\":\"0\",\"Data\":{\"TaskId\":\"task123\",\"TaskStatus\":\"FAILED\",\"ErrorCode\":\"TSC.AudioFormat\",\"ErrorMessage\":\"secret-body\"}}";
        assertThat(client.get("task123").failure()).isEqualTo(TingwuTask.FailureCategory.UNSUPPORTED_MEDIA);
        body = body.replace("FAILED", "INVALID"); assertThat(client.get("task123").status()).isEqualTo(TingwuTask.Status.INVALID);
    }
    @Test void neverAutomaticallyRetriesAndDistinguishesUncertainPaidSubmission() {
        status = 429; body = "{\"Code\":\"Throttling.User\",\"Message\":\"private provider body\"}";
        failure(() -> client.create(source, "fixture-key"), TingwuFailure.Code.THROTTLED, true);
        assertThat(calls).hasValue(1);
        status = 503; body = "{\"Code\":\"ServiceUnavailable\",\"Message\":\"private provider body\"}";
        failure(() -> client.create(source, "fixture-key"), TingwuFailure.Code.SUBMISSION_UNCERTAIN, false);
        failure(() -> client.get("task123"), TingwuFailure.Code.UNAVAILABLE, true);
        assertThat(calls).hasValue(3);
        status = 403; body = "{\"Code\":\"Forbidden\",\"Message\":\"private provider body\"}";
        failure(() -> client.get("task123"), TingwuFailure.Code.ACCESS_DENIED, false);
        status = 404; body = "{\"Code\":\"NotFound\"}";
        failure(() -> client.get("task123"), TingwuFailure.Code.TASK_NOT_FOUND, false);
    }
    @Test void timesOutWithoutRecreatingATask() {
        delay = 1500;
        failure(() -> client.create(source, "fixture-key"), TingwuFailure.Code.SUBMISSION_UNCERTAIN, false);
        assertThat(calls).hasValue(1);
    }
    @Test void rejectsWrongTaskAndNonOssResultTargets() {
        body = "{\"Code\":\"0\",\"Data\":{\"TaskId\":\"wrong-task\",\"TaskStatus\":\"ONGOING\"}}";
        failure(() -> client.get("task123"), TingwuFailure.Code.INVALID_RESPONSE, false);
        body = "{\"Code\":\"0\",\"Data\":{\"TaskId\":\"task123\",\"TaskStatus\":\"COMPLETED\",\"Result\":{\"Transcription\":\"http://127.0.0.1/private\"}}}";
        failure(() -> client.get("task123"), TingwuFailure.Code.INVALID_RESPONSE, false);
        assertThatThrownBy(() -> client.get("../../private")).isInstanceOf(IllegalArgumentException.class);
        failure(() -> client.create(URI.create("https://thirdparty.example/video"), "fixture-key"), TingwuFailure.Code.INVALID_RESPONSE, false);
        assertThat(calls).hasValue(2);
    }
    @Test void onlyCreatesCloudBeansWhenEnabledAndValidatesSecretsAndTtl() {
        new ApplicationContextRunner().withUserConfiguration(TingwuConfiguration.class)
                .withBean(TingwuProperties.class, TingwuContractTest::properties)
                .withBean(AliyunCredentialsProperties.class, () -> new AliyunCredentialsProperties("test-id", "test-secret", ""))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(TingwuClient.class));
        new ApplicationContextRunner().withUserConfiguration(TingwuConfiguration.class)
                .withPropertyValues("fragpicker.integrations.tingwu.enabled=true")
                .withBean(TingwuProperties.class, TingwuContractTest::properties)
                .withBean(AliyunCredentialsProperties.class, () -> new AliyunCredentialsProperties("", "", ""))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(ctx -> assertThat(ctx).hasFailed());
        var p = properties();
        assertThatThrownBy(() -> new TingwuProperties(true, p.appKey(), p.sourceLanguage(), p.connectTimeout(), p.readTimeout(),
                p.resultTimeout(), p.maxResultBytes(), Duration.ofMinutes(5)).validate()).hasMessageContaining("SOURCE_URL_TTL");
        assertThat(p.toString()).doesNotContain(p.appKey());
    }
    private void failure(Runnable operation, TingwuFailure.Code code, boolean retryable) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(TingwuFailure.class, error -> {
            assertThat(error.code()).isEqualTo(code); assertThat(error.retryable()).isEqualTo(retryable);
            assertThat(error.getCause()).isNull(); assertThat(error.getMessage()).doesNotContain("private").doesNotContain("signature");
        });
    }
}
