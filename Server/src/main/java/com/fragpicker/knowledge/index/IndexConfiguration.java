package com.fragpicker.knowledge.index;

import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("database")
@EnableScheduling
@ConditionalOnProperty(prefix = "fragpicker.knowledge.index", name = "enabled", havingValue = "true")
public class IndexConfiguration {
    @Bean
    IndexWorker indexWorker(IndexStore store, LocalEmbeddingService embeddings, IndexProperties properties) {
        properties.validate();
        // Load native libraries and run inference before accepting ingestion requests.
        embeddings.embedDocument("索引服务启动检查");
        return new IndexWorker(store, embeddings);
    }
}
