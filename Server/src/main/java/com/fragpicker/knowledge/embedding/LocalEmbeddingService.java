package com.fragpicker.knowledge.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;

import java.util.List;

/** Chinese ONNX inference stays in this process; no remote credentials or requests. */
public class LocalEmbeddingService {
    public static final String MODEL_ID = "bge-small-zh-v1.5-q@langchain4j-1.21.0-beta31";
    public static final int DIMENSIONS = 512;
    public static final int MAX_DOCUMENT_CODEPOINTS = 384;
    public static final int MAX_QUERY_CODEPOINTS = 256;
    private static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private final BgeSmallZhV15QuantizedEmbeddingModel model = new BgeSmallZhV15QuantizedEmbeddingModel();

    public Embedding embedQuery(String query) {
        return infer(QUERY_PREFIX + checkedText(query, MAX_QUERY_CODEPOINTS));
    }

    public Embedding embedDocument(String document) {
        return infer(checkedText(document, MAX_DOCUMENT_CODEPOINTS));
    }

    public List<Embedding> embedDocuments(List<String> documents) {
        if (documents == null || documents.isEmpty() || documents.size() > 16) {
            throw new IllegalArgumentException("Embedding batch must contain 1 to 16 documents");
        }
        // Validate the whole batch before inference; process sequentially to bound CPU pressure.
        List<String> validated = documents.stream().map(text -> checkedText(text, MAX_DOCUMENT_CODEPOINTS)).toList();
        return validated.stream().map(this::infer).toList();
    }

    private String checkedText(String text, int maxCodepoints) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding text must not be blank");
        String stripped = text.strip();
        if (stripped.codePointCount(0, stripped.length()) > maxCodepoints) {
            throw new IllegalArgumentException("Embedding text exceeds the chunk size limit");
        }
        return stripped;
    }

    // Single-document calls use the caller thread; SDK batch pools are intentionally avoided.
    private synchronized Embedding infer(String text) {
        float[] vector = model.embed(text).content().vector().clone();
        if (vector.length != DIMENSIONS) throw new IllegalStateException("Unexpected local embedding dimension");
        double squaredNorm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite local embedding");
            squaredNorm += (double) value * value;
        }
        if (squaredNorm == 0) throw new IllegalStateException("Zero local embedding");
        double norm = Math.sqrt(squaredNorm);
        for (int i = 0; i < vector.length; i++) vector[i] /= (float) norm;
        return Embedding.from(vector);
    }
}
