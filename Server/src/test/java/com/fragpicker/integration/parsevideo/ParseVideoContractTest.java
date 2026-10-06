package com.fragpicker.integration.parsevideo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.fragpicker.integration.parsevideo.ParseVideoFailure.Code.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParseVideoContractTest {
    private HttpServer server;
    private String body;
    private int status = 200;
    private long delay;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void startFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prefix/video/share/url/parse", exchange -> {
            calls.incrementAndGet();
            query.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery().substring(4), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (delay > 0) {
                try { Thread.sleep(delay); } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/should-not-follow");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
    }

    @AfterEach
    void stopFixture() { server.stop(0); }

    private ParseVideoClient client(boolean authentication) {
        var properties = new ParseVideoProperties(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/prefix/",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 16384,
                authentication ? "fixture-user" : "", authentication ? "fixture-pass" : "");
        return new ParseVideoConfiguration().parseVideoClient(properties, new ObjectMapper());
    }

    @Test
    void mapsWrapperAuthorAndUrlsAndEncodesTheEntireShareUrl() {
        body = """
                {"code":200,"msg":"成功","data":{"video_url":"https://cdn.example.com/video.mp4?sign=temporary",
                "cover_url":"https://cdn.example.com/cover.jpg","title":"导数学习资源",
                "author":{"uid":123,"name":"数学老师","avatar":"https://cdn.example.com/avatar.jpg"}}}
                """;
        String share = "https://www.bilibili.com/video/fixture?name=数学&x=1#part";
        var result = client(true).parse(share);
        assertThat(query.get()).isEqualTo(share);
        assertThat(result.title()).isEqualTo("导数学习资源");
        assertThat(result.authorName()).isEqualTo("数学老师");
        assertThat(result.authorUid()).isEqualTo("123");
        assertThat(result.videoUrl().getRawQuery()).isEqualTo("sign=temporary");
        assertThat(result.toString()).doesNotContain("temporary");
        assertThat(authorization.get()).startsWith("Basic ");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void acceptsMissingOptionalMetadataAndRejectsImageOnlyPosts() {
        body = "{\"code\":200,\"data\":{\"video_url\":\"https://cdn.example.com/a.mp4\",\"author\":null}}";
        var result = client(false).parse("https://v.douyin.com/fixture/");
        assertThat(result.coverUrl()).isNull();
        assertThat(result.authorName()).isNull();
        body = "{\"code\":200,\"data\":{\"video_url\":\"\",\"images\":[{}]}}";
        assertFailure(UNSUPPORTED_CONTENT, false);
    }

    @Test
    void distinguishesBusinessFailureAndMalformedResponseWithoutEchoingProviderMessages() {
        body = "{\"code\":400,\"msg\":\"internal secret message\"}";
        assertFailure(INVALID_LINK, false);
        body = "{\"code\":500,\"msg\":\"internal secret message\"}";
        assertFailure(PROVIDER_UNAVAILABLE, true);
        body = "not json";
        assertFailure(INVALID_RESPONSE, false);
        body = "{\"data\":{}}";
        assertFailure(INVALID_RESPONSE, false);
        body = "{\"code\":200,\"data\":{\"video_url\":\"file:///private/video.mp4\"}}";
        assertFailure(INVALID_RESPONSE, false);
    }

    @Test
    void rejectsRedirectsAndOversizedBodiesWithoutRetries() {
        status = 302; body = "{}";
        assertFailure(PROVIDER_UNAVAILABLE, false);
        assertThat(calls.get()).isEqualTo(1);
        status = 200; body = "x".repeat(20000);
        assertFailure(RESPONSE_TOO_LARGE, false);
    }

    @Test
    void timesOutAndMarksTransientFailureForWorkerRetry() {
        delay = 1500; body = "{}";
        assertFailure(TIMEOUT, true);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void rejectsIncompleteAndInsecureCredentialConfiguration() {
        var p = new ParseVideoProperties(true, "http://service.example.com", Duration.ofSeconds(1), Duration.ofSeconds(1), 16384, "u", "p");
        assertThatThrownBy(p::validateEnabled).hasMessageContaining("HTTPS");
        assertThat(p.toString()).doesNotContain("service.example.com").contains("REDACTED");
    }

    private void assertFailure(ParseVideoFailure.Code expected, boolean retryable) {
        assertThatThrownBy(() -> client(false).parse("https://v.douyin.com/fixture/"))
                .isInstanceOfSatisfying(ParseVideoFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo(expected);
                    assertThat(failure.retryable()).isEqualTo(retryable);
                    assertThat(failure.getMessage()).doesNotContain("secret");
                });
    }
}
