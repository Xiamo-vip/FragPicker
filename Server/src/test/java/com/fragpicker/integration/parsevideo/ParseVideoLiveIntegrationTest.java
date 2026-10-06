package com.fragpicker.integration.parsevideo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "PARSEVIDEO_TEST_BASE_URL", matches = ".+")
class ParseVideoLiveIntegrationTest {
    @Test
    void parsesTheUpstreamDocumentedBilibiliSampleOnTheConfiguredDeployment() {
        var properties = new ParseVideoProperties(true, System.getenv("PARSEVIDEO_TEST_BASE_URL"),
                Duration.ofSeconds(5), Duration.ofSeconds(45), 1048576, "", "");
        var video = new ParseVideoConfiguration().parseVideoClient(properties, new ObjectMapper())
                .parse("https://www.bilibili.com/video/BV1GJ411x7h7");
        assertThat(video.videoUrl().getHost()).isNotBlank();
        assertThat(video.title()).isNotBlank();
        assertThat(video.authorName()).isNotBlank();
        assertThat(video.coverUrl()).isNotNull();
    }
}
