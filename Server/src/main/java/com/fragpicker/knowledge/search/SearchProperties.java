package com.fragpicker.knowledge.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("fragpicker.knowledge.search")
public record SearchProperties(int maxChunks, Duration timeout, double minSimilarity, double literalBoost) {
    public void validate() {
        if (maxChunks < 100 || maxChunks > 1000000 || timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofMinutes(1)) > 0
                || !Double.isFinite(minSimilarity) || minSimilarity < 0 || minSimilarity > 1 || !Double.isFinite(literalBoost) || literalBoost < 0 || literalBoost > 0.3)
            throw new IllegalStateException("Invalid semantic search limits");
    }
}
