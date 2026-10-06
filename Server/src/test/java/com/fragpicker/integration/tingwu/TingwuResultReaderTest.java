package com.fragpicker.integration.tingwu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class TingwuResultReaderTest {
    private HttpServer fixture;
    private TingwuResultReader reader;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile boolean chunked;
    private volatile long delay;
    private volatile String authorization;
    private final URI transcript = URI.create("https://test-output.oss-cn-beijing.aliyuncs.com/transcript.json?sign=fixture");
    private Map<String, String> bodies;
    @BeforeEach void start() throws Exception {
        bodies = new java.util.HashMap<>(Map.of(
                "/transcript.json", """
                  {"TaskId":"fixture-id","Transcription":{"AudioInfo":{"Duration":1200},"Paragraphs":[
                   {"ParagraphId":"p1","SpeakerId":"s1","Words":[
                    {"SentenceId":1,"Start":100,"End":300,"Text":"导数"},
                    {"SentenceId":1,"Start":300,"End":800,"Text":"描述变化率。"},
                    {"SentenceId":2,"Start":900,"End":1200,"Text":"用极限求导。"}]}]}}
                  """,
                "/summary.json", "{\"TaskId\":\"fixture-id\",\"Summarization\":{\"ParagraphSummary\":\"讲解导数。\"}}",
                "/assistance.json", """
                  {"TaskId":"fixture-id","MeetingAssistance":{"Keywords":["导数","变化率"],
                   "KeySentences":[{"SentenceId":1,"Start":100,"End":800,"Text":"导数描述变化率。"}]}}
                  """));
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            calls.incrementAndGet(); authorization = exchange.getRequestHeaders().getFirst("Authorization");
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = bodies.getOrDefault(exchange.getRequestURI().getPath(), "{}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1/private");
                exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException aborted) { /* expected limit/timeout */ }
            finally { exchange.close(); }
        }); fixture.start(); reader = fixtureReader(4096, Duration.ofSeconds(2));
    }
    private TingwuResultReader fixtureReader(int limit, Duration total) {
        var p = TingwuContractTest.properties();
        var config = RequestConfig.custom().setConnectTimeout(1000).setSocketTimeout(1000).build();
        var client = HttpClients.custom().setDefaultRequestConfig(config).disableRedirectHandling().disableAutomaticRetries()
                .setRoutePlanner((target, request, context) -> new HttpRoute(new HttpHost("127.0.0.1", fixture.getAddress().getPort(), "http")))
                .build();
        return new TingwuResultReader(client, new TingwuProperties(true, p.appKey(), p.sourceLanguage(), p.connectTimeout(),
                p.readTimeout(), total, limit, p.sourceUrlTtl()), new ObjectMapper());
    }
    @AfterEach void stop() throws Exception { reader.close(); fixture.stop(0); }
    private TingwuTask task(boolean extras) {
        return new TingwuTask("fixture-id", "fixture-key", TingwuTask.Status.COMPLETED, null, transcript,
                extras ? transcript.resolve("/summary.json") : null, extras ? transcript.resolve("/assistance.json") : null);
    }
    @Test void groupsWordsIntoTimestampedSentencesAndReadsSummaryKeywordsAndPoints() {
        var result = reader.read(task(true));
        assertThat(result.durationMs()).isEqualTo(1200); assertThat(result.sentences()).hasSize(2);
        assertThat(result.sentences().getFirst().text()).isEqualTo("导数描述变化率。");
        assertThat(result.sentences().getFirst().startMs()).isEqualTo(100); assertThat(result.sentences().getFirst().endMs()).isEqualTo(800);
        assertThat(result.summary()).isEqualTo("讲解导数。"); assertThat(result.keywords()).containsExactly("导数", "变化率");
        assertThat(result.keyPoints()).hasSize(1); assertThat(authorization).isNull(); assertThat(calls).hasValue(3);
        assertThat(result.toString()).doesNotContain("导数");
    }
    @Test void allowsMissingOptionalAlgorithmsButRejectsWrongTaskSchemaAndTimestamps() {
        assertThat(reader.read(task(false)).summary()).isNull();
        String original = bodies.get("/transcript.json");
        for (String invalid : new String[]{original.replace("fixture-id", "another-id"), original.replace("\"End\":300", "\"End\":50"), "{}", "not-json"}) {
            bodies.put("/transcript.json", invalid); failure(TingwuFailure.Code.INVALID_RESPONSE, false);
        }
    }
    @Test void rejectsDeclaredAndChunkedOversizeWithoutFollowingRedirects() throws Exception {
        reader.close(); reader = fixtureReader(1024, Duration.ofSeconds(2));
        bodies.put("/transcript.json", " ".repeat(2048)); failure(TingwuFailure.Code.RESPONSE_TOO_LARGE, false);
        chunked = true; failure(TingwuFailure.Code.RESPONSE_TOO_LARGE, false);
        status = 302; failure(TingwuFailure.Code.UNAVAILABLE, false); assertThat(calls).hasValue(3);
    }
    @Test void mapsExpiredResultsAndTimeoutAndRejectsTargetsBeforeConnecting() {
        status = 403; failure(TingwuFailure.Code.RESULT_EXPIRED, true);
        status = 200; delay = 1500; failure(TingwuFailure.Code.TIMEOUT, true);
        assertThatThrownBy(() -> reader.read(new TingwuTask("fixture-id", null, TingwuTask.Status.COMPLETED, null,
                URI.create("https://attacker.example/private"), null, null))).isInstanceOf(TingwuFailure.class);
        assertThat(calls).hasValue(2);
    }
    private void failure(TingwuFailure.Code code, boolean retryable) {
        assertThatThrownBy(() -> reader.read(task(false))).isInstanceOfSatisfying(TingwuFailure.class, error -> {
            assertThat(error.code()).isEqualTo(code); assertThat(error.retryable()).isEqualTo(retryable); assertThat(error.getCause()).isNull();
        });
    }
}
