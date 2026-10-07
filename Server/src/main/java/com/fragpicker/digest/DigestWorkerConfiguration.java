package com.fragpicker.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.chat.ChatModelProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@Profile("database")
@EnableScheduling
@ConditionalOnProperty(prefix="fragpicker.digest.worker",name="enabled",havingValue="true")
public class DigestWorkerConfiguration {
    @Bean DigestWorker digestWorker(DigestStore store,DailyDigestGenerator generator,ChatModel model,
                                   ChatModelProperties chat,DigestJobProperties worker,ObjectMapper json) {
        worker.validate(); chat.validateEnabled();
        if (!chat.enabled()) throw new IllegalStateException("DIGEST_WORKER_ENABLED requires AI_CHAT_ENABLED");
        if (worker.leaseDuration().compareTo(chat.timeout().plusSeconds(15)) < 0) throw new IllegalStateException("DIGEST_LEASE_DURATION must cover chat timeout");
        return new DigestWorker(store,generator,model,chat,json);
    }
}
