package com.fragpicker.knowledge.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SearchServiceTest {
    private SearchMapper data;
    private LocalEmbeddingService model;
    private SearchService search;
    @BeforeEach void setup() {
        data = mock(SearchMapper.class); model = mock(LocalEmbeddingService.class);
        search = new SearchService(data, model, defaults(), new ObjectMapper());
        float[] unit = new float[512]; unit[0] = 1; when(model.embedQuery(anyString())).thenReturn(Embedding.from(unit));
        when(data.countChunks(any(), anyString(), anyString())).thenReturn(1L);
        when(data.card(any(), anyString(), anyString(), anyLong())).thenAnswer(call -> new SearchCard(call.getArgument(3), "资料", "作者", LocalDate.of(2026, 10, 6), "摘要", "[\"LEARNING\"]", false, false, null));
    }
    @Test void groupsAcrossPageBoundariesAndRanksLiteralMatchesWithDeterministicTies() {
        when(data.page(any(), anyString(), anyString(), eq(0L), eq(-1), anyInt())).thenReturn(List.of(chunk(1, 0, .6, "第一段"), chunk(1, 1, .7, "第二段")));
        when(data.page(any(), anyString(), anyString(), eq(1L), eq(1), anyInt())).thenReturn(List.of(chunk(1, 2, .8, "第三段"), chunk(2, 0, .8, "其他资料"), chunk(3, 0, .75, "包含导数原文")));
        when(data.page(any(), anyString(), anyString(), eq(3L), eq(0), anyInt())).thenReturn(List.of());
        var result = search.search(9, request("导数", 2));
        assertThat(result.items()).extracting(SearchResponse.Hit::fragmentId).containsExactly(3L, 1L);
        assertThat(result.items().getFirst().literalMatch()).isTrue(); assertThat(result.items().get(1).match().chunkOrdinal()).isEqualTo(2);
        assertThat(result.scannedChunks()).isEqualTo(5);
    }
    @Test void appliesThresholdAndDetectsInvalidVectorsWithoutReturningPartialResults() {
        when(data.page(any(), anyString(), anyString(), eq(0L), eq(-1), anyInt())).thenReturn(List.of(chunk(1, 0, .2, "无关资料")));
        when(data.page(any(), anyString(), anyString(), eq(1L), eq(0), anyInt())).thenReturn(List.of());
        assertThat(search.search(1, request("变化率", 10)).items()).isEmpty();
        when(data.page(any(), anyString(), anyString(), eq(0L), eq(-1), anyInt())).thenReturn(List.of(new SearchChunk(1, 0, "SUMMARY", null, null, null, "资料", new byte[2048])));
        assertCode(() -> search.search(1, request("变化率", 10)), "SEARCH_INDEX_INVALID");
    }
    @Test void validatesUnicodeDateAndResultBoundsBeforeAnyDatabaseOrModelCalls() {
        for (String query : List.of("", " ", "😀".repeat(257), "学习\n数学")) assertCode(() -> search.search(1, request(query, 10)), "INVALID_SEARCH");
        assertCode(() -> search.search(1, request("导数", 21)), "INVALID_SEARCH");
        assertCode(() -> search.search(1, new SearchRequest("导数", LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 6), null, null, null, 10)), "INVALID_SEARCH");
        assertCode(() -> search.search(1, new SearchRequest("导数", LocalDate.of(1, 1, 1), null, null, null, null, 10)), "INVALID_SEARCH");
        assertCode(() -> search.search(1, new SearchRequest("导数", null, null, null, "作者".repeat(51), null, 10)), "INVALID_SEARCH");
        verifyNoInteractions(data, model);
    }
    @Test void boundsScopeBeforeInferenceAndDoesNotLoadModelForEmptyResults() {
        when(data.countChunks(any(), anyString(), anyString())).thenReturn(0L); assertThat(search.search(1, request("导数", 10)).scannedChunks()).isZero();
        when(data.countChunks(any(), anyString(), anyString())).thenReturn(100001L); assertCode(() -> search.search(1, request("导数", 10)), "SEARCH_SCOPE_TOO_LARGE");
        verifyNoInteractions(model);
    }
    @Test void rejectsConcurrentRequestsWithoutUnboundedInferenceQueueAndReleasesPermitAfterFailure() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(data.countChunks(any(), anyString(), anyString())).thenAnswer(call -> { entered.countDown(); if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException(); return 0L; });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> search.search(1, request("导数", 10))); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try { assertCode(() -> search.search(1, request("导数", 10)), "SEARCH_BUSY"); } finally { release.countDown(); }
            assertThat(first.get(3, TimeUnit.SECONDS).items()).isEmpty();
        }
        when(data.countChunks(any(), anyString(), anyString())).thenThrow(new IllegalStateException("provider/key/SQL details"));
        assertCode(() -> search.search(1, request("导数", 10)), "SEARCH_UNAVAILABLE");
        doReturn(0L).when(data).countChunks(any(), anyString(), anyString()); assertThat(search.search(1, request("导数", 10)).items()).isEmpty();
    }
    @Test void validatesConfigurationAndDoesNotLeakInternalExceptions() {
        defaults().validate();
        assertThatThrownBy(() -> new SearchProperties(99, Duration.ofSeconds(15), .4, .1).validate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SearchProperties(100, Duration.ZERO, .4, .1).validate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SearchProperties(100, Duration.ofSeconds(15), Double.NaN, .1).validate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SearchProperties(100, Duration.ofSeconds(15), .4, .31).validate()).isInstanceOf(IllegalStateException.class);
        when(model.embedQuery(anyString())).thenThrow(new IllegalStateException("secret-supplier-response"));
        assertThatThrownBy(() -> search.search(1, request("导数", 10))).isInstanceOf(ApiException.class).hasMessageNotContaining("secret").hasNoCause();
    }
    private SearchRequest request(String query, int limit) { return new SearchRequest(query, null, null, null, null, null, limit); }
    private SearchProperties defaults() { return new SearchProperties(100000, Duration.ofSeconds(15), .4, .1); }
    private SearchChunk chunk(long id, int ordinal, double cosine, String text) {
        float[] unit = new float[512]; unit[0] = (float) cosine; unit[1] = (float) Math.sqrt(1 - cosine * cosine);
        return new SearchChunk(id, ordinal, "TRANSCRIPT", ordinal, 1000L, 2000L, text, com.fragpicker.knowledge.index.VectorCodec.encode(unit));
    }
    private void assertCode(Runnable call, String code) { assertThatThrownBy(call::run).isInstanceOf(ApiException.class).satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(code)); }
}
