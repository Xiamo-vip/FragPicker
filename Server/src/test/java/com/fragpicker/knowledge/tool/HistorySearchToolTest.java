package com.fragpicker.knowledge.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.auth.CurrentUser;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.knowledge.EnrichmentResult.Category;
import com.fragpicker.knowledge.search.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HistorySearchToolTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private SearchService search;
    private HistoryToolFactory factory;
    @BeforeEach void setup() { search = mock(SearchService.class); factory = new HistoryToolFactory(search, json); when(search.search(anyLong(), any())).thenReturn(new SearchResponse(List.of(), 0)); }
    @Test void registersOnlyReadOnlyArgumentsAndBindsOwnerOutsideModelSchema() {
        var tool = factory.bind(new CurrentUser(9L)); var specs = tool.specifications(); assertThat(specs).hasSize(1); var spec = specs.getFirst();
        assertThat(spec.name()).isEqualTo(HistorySearchTool.NAME); assertThat(spec.parameters().properties().keySet()).containsExactlyInAnyOrder("query", "fromDate", "toDate", "category", "author", "keyword");
        assertThat(spec.parameters().required()).containsExactly("query"); assertThatThrownBy(() -> factory.bind(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.bind(new CurrentUser(0L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.bind(new CurrentUser(null))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUnknownToolsOwnerInjectionDuplicateFieldsWrongTypesAndTrailingJson() throws Exception {
        for (String arguments : List.of("{\"query\":\"数学\",\"userId\":1}", "{\"query\":\"数学\",\"query\":\"做饭\"}", "{\"query\":123}", "[]", "{\"query\":\"数学\"} {}", "x".repeat(16385))) {
            var tool = factory.bind(new CurrentUser(9L)); assertThat(json.readTree(tool.execute(call(arguments))).path("errorCode").asText()).isEqualTo("INVALID_TOOL_ARGUMENTS"); assertThat(tool.cards()).isEmpty();
        }
        var unknown = ToolExecutionRequest.builder().name("runSql").arguments("{}").build(); assertThat(json.readTree(factory.bind(new CurrentUser(9L)).execute(unknown)).path("errorCode").asText()).isEqualTo("UNKNOWN_TOOL");
        verifyNoInteractions(search);
    }
    @Test void preservesServerCardsBoundsModelPreviewsAndForwardsOptionalFiltersToFixedOwner() throws Exception {
        var card = new SearchResponse.Hit(42, "😀".repeat(201), "老师", LocalDate.of(2026, 10, 6), "😀".repeat(401), List.of(Category.LEARNING), "/owned/video", "/owned/cover", .6, .6, false, new SearchResponse.Match(3, "TRANSCRIPT", 1, 1000L, 2000L, "原文片段"));
        when(search.search(eq(9L), any())).thenReturn(new SearchResponse(List.of(card), 1));
        var tool = factory.bind(new CurrentUser(9L)); var result = json.readTree(tool.execute(call("{\"query\":\"导数\",\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-06\",\"category\":\"LEARNING\",\"author\":\"老师\",\"keyword\":null}")));
        assertThat(result.path("status").asText()).isEqualTo("OK"); assertThat(result.path("items").get(0).path("titlePreview").asText()).isEqualTo("😀".repeat(200));
        assertThat(result.path("items").get(0).path("summaryPreview").asText()).isEqualTo("😀".repeat(400)); assertThat(result.toString()).doesNotContain("/owned", "userId", "embedding"); assertThat(tool.cards()).containsExactly(card);
        verify(search).search(9, new SearchRequest("导数", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 6), Category.LEARNING, "老师", null, 5));
        assertThat(factory.bind(new CurrentUser(10L)).cards()).isEmpty(); assertThat(tool.toString()).doesNotContain("😀", "老师");
    }
    @Test void boundsAttemptsIncludingBadRequestsAndDoesNotExposeSupplierErrors() throws Exception {
        var tool = factory.bind(new CurrentUser(9L));
        for (int i = 0; i < 3; i++) assertThat(json.readTree(tool.execute(call("{\"query\":\"导数\"}"))).path("status").asText()).isEqualTo("OK");
        assertThat(json.readTree(tool.execute(call("{\"query\":\"导数\"}"))).path("errorCode").asText()).isEqualTo("TOOL_LIMIT_REACHED"); verify(search, times(3)).search(eq(9L), any());
        var malformed = factory.bind(new CurrentUser(9L)); for (int i = 0; i < 3; i++) malformed.execute(call("[]")); assertThat(json.readTree(malformed.execute(call("{}"))).path("errorCode").asText()).isEqualTo("TOOL_LIMIT_REACHED");
        when(search.search(eq(10L), any())).thenThrow(new IllegalStateException("provider-secret-response"));
        var error = factory.bind(new CurrentUser(10L)).execute(call("{\"query\":\"导数\"}")); assertThat(error).contains("TOOL_UNAVAILABLE").doesNotContain("provider-secret-response");
        when(search.search(eq(11L), any())).thenThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SEARCH_BUSY", "secret-message"));
        assertThat(factory.bind(new CurrentUser(11L)).execute(call("{\"query\":\"导数\"}"))).contains("SEARCH_BUSY").doesNotContain("secret-message");
        assertThat(factory.bind(new CurrentUser(9L)).execute(call("{\"query\":\"导数\",\"category\":\"UNKNOWN\"}"))).contains("INVALID_TOOL_ARGUMENTS");
    }
    private ToolExecutionRequest call(String arguments) { return ToolExecutionRequest.builder().id("test-call").name(HistorySearchTool.NAME).arguments(arguments).build(); }
}
