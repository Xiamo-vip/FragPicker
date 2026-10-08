package com.fragpicker.ingestion.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.parsevideo.ParseVideoProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PREVIEW_LIVE_TEST_ENABLED",matches="true")
class PreviewLiveTest {
    @Test void readsTheConfiguredParserAndThePublicSampleVideoHeader() throws Exception {
        var config=new PreviewConfiguration();
        var parser=config.previewParser(new ParseVideoProperties(true,System.getenv("PARSEVIDEO_TEST_BASE_URL"),Duration.ofSeconds(3),Duration.ofSeconds(12),1048576,"",""),new ObjectMapper());
        URI source=URI.create("https://www.bilibili.com/video/BV1GJ411x7h7");
        var video=parser.parse(source.toASCIIString());
        assertThat(video.videoUrl()).isNotNull();
        try(var probe=config.mediaResourceProbe()){assertThat(probe.check(video.videoUrl(),source)).startsWith("video/");}
    }
}
