package com.fragpicker.ingestion;

import com.fragpicker.integration.oss.*;
import com.fragpicker.integration.tingwu.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("database")
@EnableScheduling
@ConditionalOnProperty(prefix = "fragpicker.ingestion.transcription-worker", name = "enabled", havingValue = "true")
public class TranscriptionWorkerConfiguration {
    @Bean
    TranscriptionWorker transcriptionWorker(TranscriptionJobStore store, OssMediaStorage storage, OssProperties oss,
            TingwuClient client, TingwuResultReader reader, TingwuProperties tingwu, TranscriptionWorkerProperties worker) {
        worker.validate(); tingwu.validate(); oss.validate();
        if (!oss.enabled() || !tingwu.enabled()) throw new IllegalStateException("TRANSCRIPTION_WORKER_ENABLED requires OSS_ENABLED and TINGWU_ENABLED");
        var budget = tingwu.connectTimeout().plus(tingwu.readTimeout()).plus(tingwu.resultTimeout().multipliedBy(3)).plusSeconds(15);
        if (worker.leaseDuration().compareTo(budget) < 0) throw new IllegalStateException("TRANSCRIPTION_LEASE_DURATION is too short for cloud and result timeouts");
        return new TranscriptionWorker(store, storage, oss, client, reader, tingwu);
    }
}
