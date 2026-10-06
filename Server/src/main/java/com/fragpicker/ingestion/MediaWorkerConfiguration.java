package com.fragpicker.ingestion;

import com.fragpicker.integration.media.*;
import com.fragpicker.integration.oss.*;
import com.fragpicker.integration.parsevideo.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("database")
@EnableScheduling
@ConditionalOnProperty(prefix = "fragpicker.ingestion.media-worker", name = "enabled", havingValue = "true")
public class MediaWorkerConfiguration {
    @Bean
    MediaStorageWorker mediaStorageWorker(MediaJobStore store, SafeMediaDownloader downloader, OssMediaStorage storage,
            ParseVideoClient parser, MediaWorkerProperties worker, MediaDownloadProperties media,
            ParseVideoProperties parse, OssProperties oss) {
        worker.validate(); media.validate(); parse.validateEnabled(); oss.validate();
        if (!parse.enabled() || !oss.enabled()) throw new IllegalStateException("MEDIA_WORKER_ENABLED requires PARSEVIDEO_ENABLED and OSS_ENABLED");
        // Allow an expired-source refresh plus another download pass. OSS idle timeout is 60 seconds,
        // not a hard upload deadline; version/owner fencing still rejects any overlong stale upload.
        var expected = media.totalTimeout().multipliedBy(4).plusSeconds(140)
                .plus(parse.connectTimeout()).plus(parse.readTimeout());
        if (worker.leaseDuration().compareTo(expected) < 0) throw new IllegalStateException("MEDIA_WORKER_LEASE_DURATION is too short for configured media timeouts");
        return new MediaStorageWorker(store, downloader, storage, parser);
    }
}
