package com.fragpicker.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.chat.ChatModelProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("database")
@EnableScheduling
@ConditionalOnProperty(prefix = "fragpicker.knowledge.enrichment", name = "enabled", havingValue = "true")
public class EnrichmentConfiguration {
    @Bean
    EnrichmentWorker enrichmentWorker(EnrichmentStore store, ChatModel model, ObjectMapper json, EnrichmentProperties worker, ChatModelProperties chat) {
        worker.validate(); chat.validateEnabled();
        if (!chat.enabled()) throw new IllegalStateException("KNOWLEDGE_ENRICHMENT_ENABLED requires AI_CHAT_ENABLED");
        if (chat.model().length() > 128) throw new IllegalStateException("AI_CHAT_MODEL must fit enrichment metadata (128 characters)");
        if (worker.leaseDuration().compareTo(chat.timeout().plusSeconds(15)) < 0) throw new IllegalStateException("KNOWLEDGE_LEASE_DURATION must cover the chat timeout");
        return new EnrichmentWorker(store, new KnowledgeEnricher(model, json), chat.model());
    }
}
