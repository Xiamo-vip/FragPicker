package com.fragpicker.knowledge.index;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class IndexFormatTest {
    @Test void preservesCompleteUnicodeTextWithOverlapAndOriginalSentenceTime() {
        String source = "学😀".repeat(450) + "最后一个知识点"; var chunks = new ArrayList<TextChunk>();
        new UnicodeChunker().append(new IndexSource("TRANSCRIPT", 130, 1000L, 9000L, source), chunks, 100);
        assertThat(chunks).hasSize(3); StringBuilder rebuilt = new StringBuilder(chunks.getFirst().content());
        for (int i = 0; i < chunks.size(); i++) {
            var text = chunks.get(i); assertThat(text.content().codePointCount(0, text.content().length())).isLessThanOrEqualTo(384);
            assertThat(text.startMs()).isEqualTo(1000L); assertThat(text.endMs()).isEqualTo(9000L); assertThat(text.sourceOrdinal()).isEqualTo(130);
            if (i > 0) {
                String previous = chunks.get(i - 1).content(); String overlap = previous.substring(previous.offsetByCodePoints(previous.length(), -48));
                assertThat(text.content()).startsWith(overlap); rebuilt.append(text.content().substring(text.content().offsetByCodePoints(0, 48)));
            }
        }
        assertThat(rebuilt.toString()).isEqualTo(source);
    }
    @Test void boundsChunksExplicitlyAndRejectsInvalidAttribution() {
        var chunker = new UnicodeChunker(); var chunks = new ArrayList<TextChunk>();
        chunker.append(new IndexSource("SUMMARY", null, null, null, " \n"), chunks, 16); assertThat(chunks).isEmpty();
        assertThatThrownBy(() -> chunker.append(new IndexSource("SUMMARY", null, null, null, "学".repeat(10000)), chunks, 16))
                .isInstanceOf(IndexFailure.class).satisfies(e -> { assertThat(((IndexFailure) e).code()).isEqualTo("INDEX_TOO_LARGE"); assertThat(((IndexFailure) e).retryable()).isFalse(); });
        assertThatThrownBy(() -> new TextChunk("TRANSCRIPT", 0, null, null, "原文")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunk("SUMMARY", null, 1L, 2L, "摘要")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunk("TRANSCRIPT", 0, 2L, 1L, "原文")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunk("UNKNOWN", null, null, null, "正文")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void encodesFixedLittleEndianNormalizedVectorsAndDefendsAgainstMutation() {
        float[] unit = new float[512]; unit[0] = 1; byte[] bytes = VectorCodec.encode(unit);
        assertThat(bytes).hasSize(2048); assertThat(Arrays.copyOf(bytes, 4)).containsExactly((byte) 0, (byte) 0, (byte) 128, (byte) 63);
        assertThat(VectorCodec.decode(bytes)).containsExactly(unit);
        var saved = new IndexedChunk(new TextChunk("NOTE", null, null, null, "学习资料"), bytes);
        Arrays.fill(bytes, (byte) 0); byte[] exposed = saved.embedding(); Arrays.fill(exposed, (byte) 0);
        assertThat(VectorCodec.decode(saved.embedding())[0]).isEqualTo(1); assertThat(saved.toString()).doesNotContain("学习资料");
        assertThatThrownBy(() -> VectorCodec.decode(new byte[2047])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VectorCodec.decode(new byte[2048])).isInstanceOf(IllegalArgumentException.class);
        unit[0] = Float.NaN; assertThatThrownBy(() -> VectorCodec.encode(unit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VectorCodec.encode(new float[511])).isInstanceOf(IllegalArgumentException.class);
    }
}
