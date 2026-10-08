package com.fragpicker.knowledge.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.knowledge.EnrichmentResult.Category;
import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import com.fragpicker.knowledge.index.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
@Profile("database")
public class SearchService {
    private static final int PAGE_SIZE = 256;
    private static final Comparator<Ranked> ORDER = Comparator.comparingDouble(Ranked::score).thenComparing(r -> r.chunk().fragmentId(), Comparator.reverseOrder());
    private final SearchMapper data;
    private final LocalEmbeddingService embeddings;
    private final SearchProperties properties;
    private final ObjectMapper json;
    private final Semaphore active = new Semaphore(1);
    public SearchService(SearchMapper data, @Lazy LocalEmbeddingService embeddings, SearchProperties properties, ObjectMapper json) {
        properties.validate(); this.data = data; this.embeddings = embeddings; this.properties = properties; this.json = json;
    }
    public SearchResponse search(long userId, SearchRequest request) {
        var scope = validate(userId, request);
        if (!active.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SEARCH_BUSY", "搜索正在处理，请稍后重试");
        try { return retrieve(scope); }
        catch (ApiException known) { throw known; }
        catch (Exception failure) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_UNAVAILABLE", "搜索暂时不可用，请稍后重试"); }
        finally { active.release(); }
    }
    private SearchResponse retrieve(SearchScope scope) {
        long deadline = System.nanoTime() + properties.timeout().toNanos();
        long count = data.countChunks(scope, LocalEmbeddingService.MODEL_ID, UnicodeChunker.VERSION);
        checkDeadline(deadline);
        if (count == 0) return new SearchResponse(List.of(), 0);
        if (count > properties.maxChunks()) throw tooLarge();
        float[] query = embeddings.embedQuery(scope.query()).vector(); VectorCodec.encode(query);
        var top = new PriorityQueue<Ranked>(ORDER); Ranked best = null; long current = -1, afterFragment = 0; int afterOrdinal = -1, scanned = 0;
        while (true) {
            checkDeadline(deadline);
            var page = data.page(scope, LocalEmbeddingService.MODEL_ID, UnicodeChunker.VERSION, afterFragment, afterOrdinal, PAGE_SIZE);
            if (page.isEmpty()) break;
            for (var chunk : page) {
                if (++scanned > properties.maxChunks()) throw tooLarge();
                if (current != chunk.fragmentId()) { offer(top, best, scope.limit()); best = null; current = chunk.fragmentId(); }
                float[] vector;
                try { vector = VectorCodec.decode(chunk.embedding()); }
                catch (IllegalArgumentException corrupt) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_INDEX_INVALID", "索引需要重新生成"); }
                double cosine = 0; for (int i = 0; i < query.length; i++) cosine += (double) query[i] * vector[i]; cosine = Math.max(-1, Math.min(1, cosine));
                boolean literal = chunk.content().toLowerCase(Locale.ROOT).contains(scope.query().toLowerCase(Locale.ROOT));
                if (cosine < properties.minSimilarity() && !literal) continue;
                var ranked = new Ranked(chunk, Math.min(1, Math.max(0, cosine) + (literal ? properties.literalBoost() : 0)), cosine, literal);
                if (best == null || ranked.score() > best.score()) best = ranked;
            }
            var last = page.getLast(); afterFragment = last.fragmentId(); afterOrdinal = last.ordinal();
        }
        offer(top, best, scope.limit()); var result = new ArrayList<SearchResponse.Hit>();
        for (var ranked : top.stream().sorted(ORDER.reversed()).toList()) {
            checkDeadline(deadline); var chunk = ranked.chunk();
            var card = data.card(scope, LocalEmbeddingService.MODEL_ID, UnicodeChunker.VERSION, chunk.fragmentId());
            if (card == null) continue; // Deletion or state/filter changes during this search.
            List<Category> categories;
            try {
                var nodes = json.readTree(card.categories()); if (!nodes.isArray() || nodes.size() < 1 || nodes.size() > 3) throw new IllegalArgumentException();
                categories = new ArrayList<>(); for (var node : nodes) { if (!node.isTextual()) throw new IllegalArgumentException(); categories.add(Category.valueOf(node.asText())); }
            } catch (Exception corrupt) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_INDEX_INVALID", "索引需要重新生成"); }
            String media = "/api/v1/fragments/" + card.fragmentId() + "/media?kind=";
            result.add(new SearchResponse.Hit(card.fragmentId(), card.title() == null || card.title().isBlank() ? "未命名视频" : card.title(), card.author(), card.businessDate(), card.summary(), categories,
                    card.video() ? media + "VIDEO" : null, card.cover() ? media + "COVER" : null, ranked.score(), ranked.cosine(), ranked.literal(),
                    new SearchResponse.Match(chunk.ordinal(), chunk.sourceKind(), chunk.sourceOrdinal(), chunk.startMs(), chunk.endMs(), chunk.content()), card.introduction()));
        }
        checkDeadline(deadline); return new SearchResponse(result, scanned);
    }
    private void offer(PriorityQueue<Ranked> heap, Ranked item, int limit) {
        if (item == null) return; heap.offer(item); if (heap.size() > limit) heap.poll();
    }
    private SearchScope validate(long userId, SearchRequest request) {
        if (userId < 1 || request == null) throw invalid();
        String query = checked(request.query(), 256); if (query == null) throw invalid();
        int limit = request.limit() == null ? 10 : request.limit(); if (limit < 1 || limit > 20) throw invalid();
        for (var date : new java.time.LocalDate[]{request.fromDate(), request.toDate()}) if (date != null && (date.getYear() < 1000 || date.getYear() > 9999)) throw invalid();
        if (request.fromDate() != null && request.toDate() != null && request.fromDate().isAfter(request.toDate())) throw invalid();
        return new SearchScope(userId, query, request.fromDate(), request.toDate(), request.category() == null ? null : request.category().name(), checked(request.author(), 100), checked(request.keyword(), 64), limit);
    }
    private String checked(String value, int max) {
        if (value == null) return null; String text = value.strip(); if (text.isEmpty() || text.codePointCount(0, text.length()) > max || text.codePoints().anyMatch(Character::isISOControl)) throw invalid(); return text;
    }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH", "请检查搜索文字、日期和筛选条件"); }
    private ApiException tooLarge() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_SCOPE_TOO_LARGE", "搜索范围过大，请缩小日期或筛选条件"); }
    private void checkDeadline(long deadline) { if (System.nanoTime() > deadline) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_TIMEOUT", "搜索超时，请缩小范围后重试"); }
    private record Ranked(SearchChunk chunk, double score, double cosine, boolean literal) { }
}
