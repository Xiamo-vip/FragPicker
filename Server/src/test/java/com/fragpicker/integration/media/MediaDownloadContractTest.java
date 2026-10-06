package com.fragpicker.integration.media;

import com.fragpicker.integration.oss.MediaKind;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static com.fragpicker.integration.media.MediaDownloadFailure.Code.*;

class MediaDownloadContractTest {
    @TempDir Path directory;
    private HttpServer fixture;
    private SafeMediaDownloader downloader;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile byte[] bytes = new byte[]{0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109, 0, 0, 0, 0};
    private volatile String redirect = "/next";
    private volatile boolean chunked;
    private volatile long delay;
    private volatile boolean trickle;
    private volatile String referer;
    private final URI source = URI.create("https://www.bilibili.com/video/fixture?private=query");
    private final URI resource = URI.create("http://video.fixture.example/media?signature=temporary");

    @BeforeEach void start() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            calls.incrementAndGet(); referer = exchange.getRequestHeaders().getFirst("Referer");
            try {
                if (delay > 0) Thread.sleep(delay);
                exchange.getResponseHeaders().set("Location", redirect);
                exchange.getResponseHeaders().set("Content-Type", "text/html"); // Body sniffing overrides an untrusted header.
                exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
                try (var out = exchange.getResponseBody()) {
                    if (trickle) { for (byte b : bytes) { out.write(b); out.flush(); Thread.sleep(50); } }
                    else out.write(bytes);
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException closedByClient) { /* Deliberate limit/timeout abort. */ }
            finally { exchange.close(); }
        });
        fixture.start();
        downloader = fixtureClient(1024, Duration.ofSeconds(5), 1000);
    }

    @AfterEach void close() throws Exception { if (downloader != null) downloader.close(); if (fixture != null) fixture.stop(0); }

    @Test void streamsToTempFileDetectsTypeAndDeletesWhenClosed() throws Exception {
        Path file;
        try (var result = downloader.download(resource, source, MediaKind.VIDEO)) {
            file = result.file();
            assertThat(Files.readAllBytes(file)).containsExactly(bytes);
            assertThat(result.contentType()).isEqualTo("video/mp4");
            assertThat(result.sizeBytes()).isEqualTo(bytes.length);
            assertThat(result.toString()).doesNotContain("temporary").doesNotContain(directory.toString());
        }
        assertThat(Files.exists(file)).isFalse();
        assertThat(referer).isEqualTo("https://www.bilibili.com/");
    }

    @Test void checksRelativeRedirectsAndRejectsPrivateTargetWithoutFollowingIt() throws Exception {
        status = 302; redirect = "http://127.0.0.1/admin";
        failure(TARGET_REJECTED, false);
        assertThat(calls).hasValue(1);
        redirect = "/loop";
        failure(REDIRECT_LIMIT, false);
        assertThat(calls).hasValue(5); // Initial failed request, then original plus three allowed redirect hops.
        emptyDirectory();
    }

    @Test void rejectsOversizedDeclaredAndChunkedBodiesAndCleansPartialFiles() throws Exception {
        downloader.close(); downloader = fixtureClient(8, Duration.ofSeconds(5), 1000);
        failure(FILE_TOO_LARGE, false); emptyDirectory();
        chunked = true;
        failure(FILE_TOO_LARGE, false); emptyDirectory();
    }

    @Test void rejectsHtmlAndWrongMediaKindEvenWhenProviderClaimedSuccess() throws Exception {
        bytes = "<html>unexpected provider page</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        failure(UNSUPPORTED_CONTENT, false); emptyDirectory();
        bytes = new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10};
        failure(UNSUPPORTED_CONTENT, false);
        try (var cover = downloader.download(resource, source, MediaKind.COVER)) { assertThat(cover.contentType()).isEqualTo("image/png"); }
        emptyDirectory();
    }

    @Test void mapsExpiredAndTransientFailuresWithoutAutomaticRetries() {
        status = 403; failure(SOURCE_EXPIRED, true);
        status = 503; failure(SOURCE_REJECTED, true);
        status = 404; failure(SOURCE_REJECTED, false);
        assertThat(calls).hasValue(3);
    }

    @Test void enforcesIdleAndTotalTimeoutAndCleansTemporaryFiles() throws Exception {
        downloader.close(); downloader = fixtureClient(1024, Duration.ofSeconds(3), 250);
        delay = 600;
        failure(TIMEOUT, true); emptyDirectory();
        // A peer producing bytes frequently should still be aborted by the total deadline.
        fixture.stop(0); downloader.close();
        start(); downloader.close(); downloader = fixtureClient(1024, Duration.ofSeconds(1), 1000);
        bytes = new byte[128]; bytes[3] = 24;
        System.arraycopy("ftypisom".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 4, 8);
        chunked = true; trickle = true; delay = 0;
        long before = System.nanoTime();
        failure(TIMEOUT, true);
        assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(3));
        emptyDirectory();
    }

    @Test void rejectsDnsAnswersBeforeAnySocketConnectsToPrivateAddresses() throws Exception {
        downloader.close();
        var client = HttpClients.custom().setDnsResolver(host -> PublicNetworkPolicy.requirePublic(new InetAddress[]{InetAddress.getByName("127.0.0.1")}))
                .disableAutomaticRetries().disableRedirectHandling().build();
        downloader = new SafeMediaDownloader(client, properties(1024, Duration.ofSeconds(3)));
        failure(TARGET_REJECTED, false);
        assertThat(calls).hasValue(0);
    }

    private SafeMediaDownloader fixtureClient(long limit, Duration total, int idle) {
        // Test-only transport route to the local fixture; production always uses validated DNS socket addresses.
        var client = HttpClients.custom().setRoutePlanner((target, request, context) ->
                        new HttpRoute(new HttpHost("127.0.0.1", fixture.getAddress().getPort(), "http")))
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(1000).setSocketTimeout(idle).build())
                .disableRedirectHandling().disableAutomaticRetries().disableContentCompression().disableCookieManagement().build();
        return new SafeMediaDownloader(client, properties(limit, total));
    }
    private MediaDownloadProperties properties(long limit, Duration total) {
        return new MediaDownloadProperties(directory, limit, limit, Duration.ofSeconds(1), Duration.ofMillis(250), total, 3);
    }
    private void failure(MediaDownloadFailure.Code code, boolean retryable) {
        assertThatThrownBy(() -> downloader.download(resource, source, MediaKind.VIDEO))
                .isInstanceOfSatisfying(MediaDownloadFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code); assertThat(failure.retryable()).isEqualTo(retryable);
                    assertThat(failure.getCause()).isNull(); assertThat(failure.getMessage()).doesNotContain("signature");
                });
    }
    private void emptyDirectory() throws Exception { try (var files = Files.list(directory)) { assertThat(files.count()).isZero(); } }
}
