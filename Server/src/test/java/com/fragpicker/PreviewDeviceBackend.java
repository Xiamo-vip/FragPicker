package com.fragpicker;

import com.fragpicker.ingestion.preview.PreviewParser;
import com.fragpicker.integration.media.MediaResourceProbe;
import com.fragpicker.integration.media.MediaDownloadFailure;
import com.fragpicker.integration.parsevideo.ParsedVideo;
import com.fragpicker.integration.parsevideo.ParseVideoFailure;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import java.net.URI;
import java.time.Duration;

/** Test classes only. Real HTTP/auth/MySQL; deterministic external preview, no cloud requests. */
public class PreviewDeviceBackend {
    public static void main(String[] args) {
        requireIsolatedEnvironment();
        var application = new SpringApplication(FragPickerApplication.class, Fixture.class);
        application.setAdditionalProfiles("android-preview-fixture");
        application.run(args);
    }

    static void requireIsolatedEnvironment() {
        if (!"true".equals(System.getenv("ANDROID_PREVIEW_FIXTURE"))
            || System.getenv("DB_URL") == null
            || !System.getenv("DB_URL").matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/fragpicker_test\\?.+")) {
            throw new IllegalStateException("Preview fixture requires a disposable loopback test database");
        }
        for (String flag : new String[]{"AI_CHAT_ENABLED","PARSEVIDEO_ENABLED","OSS_ENABLED","TINGWU_ENABLED",
            "INGESTION_WORKER_ENABLED","MEDIA_WORKER_ENABLED","TRANSCRIPTION_WORKER_ENABLED","MEDIA_CLEANUP_ENABLED",
            "KNOWLEDGE_ENRICHMENT_ENABLED","KNOWLEDGE_INDEX_ENABLED","DIGEST_WORKER_ENABLED","DIGEST_SCHEDULE_ENABLED"}) {
            if (!"false".equals(System.getenv(flag))) throw new IllegalStateException("Preview fixture requires disabled external services");
        }
    }

    @Configuration(proxyBeanMethods=false)
    @Profile("android-preview-fixture")
    public static class Fixture {
        @Bean PreviewParser devicePreviewParser() {
            return new PreviewParser(null) {
                @Override public ParsedVideo parse(String link) {
                    var source = URI.create(link);
                    if (!"b23.tv".equals(source.getHost()) || !source.getPath().startsWith("/Clipboard"))
                        throw new ParseVideoFailure(ParseVideoFailure.Code.UNSUPPORTED_CONTENT, false);
                    return new ParsedVideo("剪切板数学资源", URI.create(source.getPath().contains("Unavailable")
                        ? "https://video.fixture.example/expired.mp4" : "https://video.fixture.example/sample.mp4"),
                        null, "测试作者", null, null);
                }
            };
        }
        @Bean(destroyMethod="close") MediaResourceProbe deviceMediaProbe() {
            return new MediaResourceProbe(null, Duration.ofSeconds(1)) {
                @Override public String check(URI resource, URI source) {
                    if (resource.getPath().contains("expired"))
                        throw new MediaDownloadFailure(MediaDownloadFailure.Code.SOURCE_EXPIRED, false);
                    return "video/mp4";
                }
                @Override public void close() {}
            };
        }
    }
}