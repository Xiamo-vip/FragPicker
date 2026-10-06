package com.fragpicker.knowledge.embedding;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
public class LocalEmbeddingConfiguration {
    @Bean
    @Lazy
    LocalEmbeddingService localEmbeddingService() {
        return new LocalEmbeddingService();
    }
}
