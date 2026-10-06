package com.fragpicker.integration.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.parsevideo.*;
import com.fragpicker.integration.oss.MediaKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MEDIA_TEST_PARSEVIDEO_BASE_URL", matches = ".+")
class MediaDownloadLiveIntegrationTest {
    @TempDir Path directory;

    @Test void downloadsRealParsedVideoAndCoverWithProductionNetworkPolicy() throws Exception {
        var parserProperties = new ParseVideoProperties(true, System.getenv("MEDIA_TEST_PARSEVIDEO_BASE_URL"), Duration.ofSeconds(5),
                Duration.ofSeconds(45), 1048576, "", "");
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(5000); factory.setReadTimeout(45000);
        var parser = new ParseVideoClient(RestClient.builder().requestFactory(factory).build(), parserProperties, new ObjectMapper());
        URI source = URI.create("https://www.bilibili.com/video/BV1GJ411x7h7");
        var parsed = parser.parse(source.toString());
        var properties = new MediaDownloadProperties(directory, 128L * 1024 * 1024, 10L * 1024 * 1024,
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5), 3);
        try (var downloader = new MediaDownloadConfiguration().safeMediaDownloader(properties);
             var cover = downloader.download(parsed.coverUrl(), source, MediaKind.COVER);
             var video = downloader.download(parsed.videoUrl(), source, MediaKind.VIDEO)) {
            assertThat(cover.sizeBytes()).isPositive(); assertThat(video.sizeBytes()).isPositive();
            assertThat(cover.contentType()).startsWith("image/"); assertThat(video.contentType()).startsWith("video/");
            System.out.printf("Real media downloaded: cover=%d bytes, video=%d bytes%n", cover.sizeBytes(), video.sizeBytes());
        }
        try (var files = Files.list(directory)) { assertThat(files.count()).isZero(); }
    }
}
