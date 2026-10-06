package com.fragpicker.ingestion;

import com.fragpicker.integration.parsevideo.ParseVideoClient;
import com.fragpicker.integration.parsevideo.ParseVideoProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("database")
@EnableScheduling
@ConditionalOnProperty(prefix = "fragpicker.ingestion.worker", name = "enabled", havingValue = "true")
public class ParseWorkerConfiguration {
    @Bean
    ParseVideoWorker parseVideoWorker(ParseJobStore store, ParseVideoClient client,
                                      ParseWorkerProperties worker, ParseVideoProperties parser) {
        worker.validate();
        parser.validateEnabled();
        if (!parser.enabled()) throw new IllegalStateException("INGESTION_WORKER_ENABLED requires PARSEVIDEO_ENABLED");
        if (worker.leaseDuration().compareTo(parser.connectTimeout().plus(parser.readTimeout()).plusSeconds(5)) < 0) {
            throw new IllegalStateException("INGESTION_LEASE_DURATION must exceed configured parse timeouts by 5 seconds");
        }
        return new ParseVideoWorker(store, client);
    }
}
