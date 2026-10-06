package com.fragpicker.knowledge.embedding;

import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the bundled quantized model, not a generated vector or model mock. */
class LocalEmbeddingTest {
    private static LocalEmbeddingService embeddings;

    @BeforeAll
    static void loadModel() { embeddings = new LocalEmbeddingService(); }

    @Test
    void retrievesDerivativeResourceForRateOfChangeQuery() {
        var query = embeddings.embedQuery("我想学习函数在某一点的瞬时变化率，找之前保存的数学学习资源");
        var documents = embeddings.embedDocuments(List.of(
                "数学课程：导数的定义和计算。通过切线斜率与极限理解微分，讲解求导公式和函数单调性。",
                "家常菜教程：番茄炒鸡蛋的做法，食材准备、油温控制和调味步骤。",
                "旅游攻略：深圳周末出游，海边景点与公共交通路线。"));
        double relevant = cosine(query, documents.getFirst());
        double cooking = cosine(query, documents.get(1));
        double travel = cosine(query, documents.get(2));
        assertThat(relevant).isGreaterThan(cooking + 0.05).isGreaterThan(travel + 0.05);
        System.out.printf("Local Chinese retrieval: derivative=%.4f, cooking=%.4f, travel=%.4f%n", relevant, cooking, travel);
    }

    @Test
    void returnsStableNormalizedFiniteVectors() {
        var first = embeddings.embedDocument("导数与微积分课程");
        var second = embeddings.embedDocument("导数与微积分课程");
        assertThat(first.vector()).hasSize(LocalEmbeddingService.DIMENSIONS);
        assertThat(cosine(first, second)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.00001));
        double norm = 0;
        for (float value : first.vector()) { assertThat(Float.isFinite(value)).isTrue(); norm += (double) value * value; }
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.00001));
    }

    @Test
    void rejectsBlankOversizedAndUnboundedBatchInputs() {
        assertThatThrownBy(() -> embeddings.embedQuery(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embeddings.embedDocument("学".repeat(385))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embeddings.embedQuery("学".repeat(257))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embeddings.embedDocuments(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embeddings.embedDocuments(java.util.Collections.nCopies(17, "课程")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private double cosine(Embedding a, Embedding b) {
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.vector().length; i++) {
            dot += (double) a.vector()[i] * b.vector()[i];
            aa += (double) a.vector()[i] * a.vector()[i];
            bb += (double) b.vector()[i] * b.vector()[i];
        }
        return dot / Math.sqrt(aa * bb);
    }
}
