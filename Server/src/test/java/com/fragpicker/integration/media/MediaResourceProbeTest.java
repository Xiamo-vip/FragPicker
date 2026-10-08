package com.fragpicker.integration.media;

import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class MediaResourceProbeTest {
    private HttpServer server;
    private MediaResourceProbe probe;
    private final AtomicInteger calls=new AtomicInteger();
    private volatile byte[] body=new byte[]{0,0,0,24,102,116,121,112,105,115,111,109,0,0,0,0};
    private volatile int status=200;
    private volatile String redirect="http://127.0.0.1/private";
    private volatile String range,referer;
    private volatile boolean trickle;
    private final URI resource=URI.create("http://video.fixture.example/media?signature=private");
    private final URI source=URI.create("https://b23.tv/share?private=note");
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet(); range=exchange.getRequestHeaders().getFirst("Range"); referer=exchange.getRequestHeaders().getFirst("Referer");
            exchange.getResponseHeaders().set("Location",redirect); exchange.getResponseHeaders().set("Content-Type","text/html");
            exchange.sendResponseHeaders(status,trickle?0:body.length);
            try(var output=exchange.getResponseBody()) {
                if(trickle)for(byte b:body){output.write(b);output.flush();try{Thread.sleep(80);}catch(InterruptedException ignored){Thread.currentThread().interrupt();break;}}
                else output.write(body);
            } catch(java.io.IOException ignored) {} finally {exchange.close();}
        });
        server.start();
        var client=HttpClients.custom().setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(1000).setSocketTimeout(1000).build())
            .setRoutePlanner((target,request,context)->new HttpRoute(new HttpHost("127.0.0.1",server.getAddress().getPort())))
            .disableRedirectHandling().disableAutomaticRetries().build();
        probe=new MediaResourceProbe(client,Duration.ofMillis(400));
    }
    @AfterEach void close() throws Exception {probe.close();server.stop(0);}
    @Test void readsPlayableVideoHeaderAndOnlySendsThePlatformOriginAsReferer() {
        assertThat(probe.check(resource,source)).isEqualTo("video/mp4");
        assertThat(range).isEqualTo("bytes=0-31"); assertThat(referer).isEqualTo("https://b23.tv/");
    }
    @Test void rejectsPrivateRedirectBeforeIssuingAnotherRequest() {
        status=302;
        assertThatThrownBy(()->probe.check(resource,source)).isInstanceOfSatisfying(MediaDownloadFailure.class,error->assertThat(error.code()).isEqualTo(MediaDownloadFailure.Code.TARGET_REJECTED));
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void rejectsExpiredLinksAndHtmlInsteadOfShowingAnIngestionPrompt() {
        status=403;
        assertThatThrownBy(()->probe.check(resource,source)).isInstanceOfSatisfying(MediaDownloadFailure.class,error->assertThat(error.code()).isEqualTo(MediaDownloadFailure.Code.SOURCE_EXPIRED));
        status=200;body="<html>not a video</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(()->probe.check(resource,source)).isInstanceOfSatisfying(MediaDownloadFailure.class,error->assertThat(error.code()).isEqualTo(MediaDownloadFailure.Code.UNSUPPORTED_CONTENT));
    }
    @Test void abortsTricklingResponseAtTheTotalDeadline() {
        trickle=true; long started=System.nanoTime();
        assertThatThrownBy(()->probe.check(resource,source)).isInstanceOfSatisfying(MediaDownloadFailure.class,error->assertThat(error.code()).isEqualTo(MediaDownloadFailure.Code.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(2));
    }
}
