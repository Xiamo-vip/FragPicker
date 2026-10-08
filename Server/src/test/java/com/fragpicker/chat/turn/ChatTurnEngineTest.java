package com.fragpicker.chat.turn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.auth.CurrentUser;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.knowledge.search.*;
import com.fragpicker.knowledge.tool.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatTurnEngineTest {
    private StreamingChatModel model;
    private SearchService search;
    private ChatTurnEngine engine;
    private FakeHandle handle;
    @BeforeEach void setup() {
        model = mock(StreamingChatModel.class); search = mock(SearchService.class); handle = new FakeHandle();
        when(search.search(anyLong(), any())).thenReturn(new SearchResponse(List.of(), 0));
        engine = engine(model, new ChatTurnProperties(Duration.ofSeconds(3), 1));
    }
    @Test void forwardsGenuineDeltasMaintainsRolesAndExcludesReasoningFromPublicResult() {
        var request = new AtomicReference<ChatRequest>(); var chunks = new ArrayList<String>();
        doAnswer(invocation -> {
            request.set(invocation.getArgument(0)); StreamingChatResponseHandler callback = invocation.getArgument(1);
            callback.onPartialThinking(new PartialThinking("private-reasoning"), new PartialThinkingContext(handle));
            emit(callback, "这是", "回答"); complete(callback, AiMessage.builder().text("这是回答").thinking("private-reasoning").build()); return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var result = stream(List.of(new ConversationExchange("旧问题", "旧答案")), "新问题", new TurnCancellation(), new ChatTurnListener() { @Override public void delta(int round, String text) { chunks.add(text); } });
        assertThat(chunks).containsExactly("这是", "回答"); assertThat(result.answer()).isEqualTo("这是回答"); assertThat(result.cards()).isEmpty(); assertThat(result.modelRounds()).isEqualTo(1);
        assertThat(request.get().messages()).extracting(ChatMessage::type).containsExactly(ChatMessageType.SYSTEM, ChatMessageType.USER, ChatMessageType.AI, ChatMessageType.USER);
        assertThat(request.get().toolSpecifications()).extracting(spec -> spec.name()).containsExactlyInAnyOrder(HistorySearchTool.NAME,HistorySearchTool.DIGEST_NAME);
        assertThat(((SystemMessage)request.get().messages().getFirst()).text()).contains("北京时间今天是", "getDailyDigest", "Markdown 正文", "先直接给结论", "不复述问题", "搜索候选", "只返回那个视频", "不汇报检索和筛选过程");
        assertThat(result.toString()).doesNotContain("回答", "private-reasoning");
    }
    @Test void dispatchesOwnedToolAndKeepsOriginalReasoningForProviderContinuationAndTrustedCards() {
        var card = card(); when(search.search(eq(9L), any())).thenReturn(new SearchResponse(List.of(card), 1));
        var messages = new ArrayList<ChatRequest>(); var index = new AtomicInteger(); var endings = new ArrayList<Boolean>();
        var call = call("one"); var original = AiMessage.builder().thinking("needed-to-continue").toolExecutionRequests(List.of(call)).build();
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0); messages.add(request); StreamingChatResponseHandler callback = invocation.getArgument(1);
            if (index.getAndIncrement() == 0) complete(callback, original); else { emit(callback, "[资料42]"); complete(callback, AiMessage.from("[资料42]")); } return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var result = stream(List.of(), "找导数", new TurnCancellation(), new ChatTurnListener() { @Override public void roundEnded(int round, boolean intermediate) { endings.add(intermediate); } });
        assertThat(result.cards()).containsExactly(card); assertThat(result.toolCalls()).isEqualTo(1); assertThat(endings).containsExactly(true, false);
        assertThat(messages.get(1).messages().get(2)).isSameAs(original); assertThat(messages.get(1).messages().get(3)).isInstanceOf(ToolExecutionResultMessage.class);
        verify(search).search(eq(9L), any());
    }
    @Test void boundsToolLoopAndRemovesToolsAfterThreeAttempts() {
        var index = new AtomicInteger(); var requests = new ArrayList<ChatRequest>();
        doAnswer(invocation -> { ChatRequest request = invocation.getArgument(0); requests.add(request); StreamingChatResponseHandler callback = invocation.getArgument(1); int n = index.getAndIncrement();
            if (n < 3) complete(callback, AiMessage.from(call("call-" + n))); else { emit(callback, "没有找到"); complete(callback, AiMessage.from("没有找到")); } return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var result = stream(List.of(), "查资料", new TurnCancellation(), new ChatTurnListener() { }); assertThat(result.modelRounds()).isEqualTo(4); assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(requests.getLast().toolSpecifications()).isNullOrEmpty(); verify(search, times(3)).search(eq(9L), any());
        doAnswer(invocation -> { complete(invocation.getArgument(1), AiMessage.from(call("1"), call("2"), call("3"), call("4"))); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertCode(() -> stream(List.of(), "查资料", new TurnCancellation(), new ChatTurnListener() { }), "CHAT_TOOL_LIMIT");
    }
    @Test void returnsOnlyTheRequestedCitedVideoInsteadOfAllSearchCandidates() {
        var a = card(); var b = card(43); var c = card(44);
        when(search.search(eq(9L), any())).thenReturn(new SearchResponse(List.of(b, a, c), 3));
        finalAnswerAfterSearch("找到 A 视频：[资料42]，讲解导数的定义。");
        var result = stream(List.of(), "帮我找 A 视频", new TurnCancellation(), new ChatTurnListener() { });
        assertThat(result.cards()).containsExactly(a);
        assertThat(result.answer()).doesNotContain("其余结果", "未纳入");
    }
    @Test void omitsAllCandidateCardsWhenAnswerFindsNoRelevantVideo() {
        when(search.search(eq(9L), any())).thenReturn(new SearchResponse(List.of(card(), card(43)), 2));
        finalAnswerAfterSearch("没有找到明确匹配的视频，可以补充作者或标题中的词吗？");
        assertThat(stream(List.of(), "找 A 视频", new TurnCancellation(), new ChatTurnListener() { }).cards()).isEmpty();
    }
    @Test void selectsCitationsAcrossSearchesInAnswerOrderAndDeduplicatesThem() {
        when(search.search(eq(9L), any())).thenReturn(new SearchResponse(List.of(card(), card(43)), 2), new SearchResponse(List.of(card(44)), 1));
        var index = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler callback = invocation.getArgument(1); int n = index.getAndIncrement();
            if (n < 2) complete(callback, AiMessage.from(call("search-" + n)));
            else complete(callback, AiMessage.from("先看 [资料44]，再看 [资料42]。[资料44] 可作练习。"));
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertThat(stream(List.of(), "找课程和练习", new TurnCancellation(), new ChatTurnListener() { }).cards())
                .extracting(SearchResponse.Hit::fragmentId).containsExactly(44L, 42L);
    }
    @Test void refusesBrokenToolProtocolAndSupplierFailureWithoutExposingBodyOrRetrying() {
        doAnswer(invocation -> { complete(invocation.getArgument(1), AiMessage.from(call("same"), call("same"))); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertCode(() -> stream(List.of(), "查资料", new TurnCancellation(), new ChatTurnListener() { }), "CHAT_TOOL_INVALID"); verifyNoInteractions(search);
        reset(model); doAnswer(invocation -> { StreamingChatResponseHandler callback = invocation.getArgument(1); callback.onError(new IllegalStateException("supplier-secret-body")); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertThatThrownBy(() -> stream(List.of(), "查资料", new TurnCancellation(), new ChatTurnListener() { })).isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.code()).isEqualTo("CHAT_PROVIDER_UNAVAILABLE"); assertThat(e.getCause()).isNull(); assertThat(e.getMessage()).doesNotContain("supplier-secret-body"); });
        verify(model, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }
    @Test void cancelsOnDisconnectAndSuppressesLateCallbacksAndReleasesCapacity() {
        var late = new AtomicReference<StreamingChatResponseHandler>();
        doAnswer(invocation -> { StreamingChatResponseHandler callback = invocation.getArgument(1); late.set(callback); emit(callback, "第一段"); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var cancellation = new TurnCancellation(); var seen = new ArrayList<String>();
        assertCode(() -> stream(List.of(), "问题", cancellation, new ChatTurnListener() { @Override public void delta(int round, String text) { seen.add(text); cancellation.cancel(); } }), "CHAT_CANCELLED");
        assertThat(handle.isCancelled()).isTrue(); emit(late.get(), "晚到内容"); complete(late.get(), AiMessage.from("晚到内容")); assertThat(seen).containsExactly("第一段");
        doAnswer(invocation -> { StreamingChatResponseHandler callback = invocation.getArgument(1); emit(callback, "重连"); complete(callback, AiMessage.from("重连")); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertThat(stream(List.of(), "问题", new TurnCancellation(), new ChatTurnListener() { }).answer()).isEqualTo("重连");
    }
    @Test void timesOutQuietStreamsAndRefusesConcurrentTurnsInsteadOfQueuing() throws Exception {
        engine = engine(model, new ChatTurnProperties(Duration.ofSeconds(1), 1)); var started = new CountDownLatch(1);
        doAnswer(invocation -> { started.countDown(); StreamingChatResponseHandler callback = invocation.getArgument(1); callback.onPartialThinking(new PartialThinking("思考"), new PartialThinkingContext(handle)); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> stream(List.of(), "问题", new TurnCancellation(), new ChatTurnListener() { })); assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertCode(() -> stream(List.of(), "另一个问题", new TurnCancellation(), new ChatTurnListener() { }), "CHAT_BUSY");
            assertThatThrownBy(() -> first.get(3, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class, e -> assertThat(((ApiException) e.getCause()).code()).isEqualTo("CHAT_TIMEOUT"));
        }
        assertThat(handle.isCancelled()).isTrue();
    }
    @Test void boundsCallbackBuffersOutputAndContextWithoutExposingReasoning() {
        doAnswer(invocation -> { StreamingChatResponseHandler callback = invocation.getArgument(1); for (int i = 0; i < 129; i++) emit(callback, "字"); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertCode(() -> stream(List.of(), "问题", new TurnCancellation(), new ChatTurnListener() { }), "CHAT_STREAM_BACKPRESSURE");
        doAnswer(invocation -> { StreamingChatResponseHandler callback = invocation.getArgument(1); callback.onPartialThinking(new PartialThinking("x".repeat(64001)), new PartialThinkingContext(handle)); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertCode(() -> stream(List.of(), "问题", new TurnCancellation(), new ChatTurnListener() { }), "CHAT_OUTPUT_INVALID");
        var request = new AtomicReference<ChatRequest>(); doAnswer(invocation -> { request.set(invocation.getArgument(0)); StreamingChatResponseHandler callback = invocation.getArgument(1); emit(callback, "答案"); complete(callback, AiMessage.from("答案")); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var history = Collections.nCopies(10, new ConversationExchange("历史问题", "历史答案")); var result = stream(history, "新问题", new TurnCancellation(), new ChatTurnListener() { });
        assertThat(result.contextTruncated()).isTrue(); assertThat(request.get().messages()).hasSize(18);
        var longHistory = Collections.nCopies(3, new ConversationExchange("q", "x".repeat(15000))); result = stream(longHistory, "新问题", new TurnCancellation(), new ChatTurnListener() { }); assertThat(result.contextTruncated()).isTrue(); assertThat(request.get().messages()).hasSize(4);
    }
    @Test void rejectsInvalidInputBeforeCallingModelAndSupportsDisabledConfiguration() {
        for (String question : Arrays.asList(null, " ", "😀".repeat(2001), "\uD800", "bad\u0000value")) assertCode(() -> stream(List.of(), question, new TurnCancellation(), new ChatTurnListener() { }), "INVALID_CHAT_MESSAGE");
        var cancelled = new TurnCancellation(); cancelled.cancel(); assertCode(() -> stream(List.of(), "问题", cancelled, new ChatTurnListener() { }), "CHAT_CANCELLED"); verifyNoInteractions(model);
        var disabled = engine(null, new ChatTurnProperties(Duration.ofSeconds(3), 1)); assertCode(() -> disabled.stream(new CurrentUser(9L), List.of(), "问题", new TurnCancellation(), new ChatTurnListener() { }), "CHAT_DISABLED");
        assertThatThrownBy(() -> new ChatTurnProperties(Duration.ZERO, 1).validate()).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsFormalCitationIdsThatWereNotVerifiedByThisTurnsTool() {
        for (String invented : List.of("[资料43]", "[资料99999999999999999999]")) {
            doAnswer(invocation -> { StreamingChatResponseHandler callback = invocation.getArgument(1); emit(callback, invented); complete(callback, AiMessage.from(invented)); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            assertCode(() -> stream(List.of(), "问题", new TurnCancellation(), new ChatTurnListener() { }), "CHAT_CITATION_INVALID");
        }
        verifyNoInteractions(search);
    }
    @SuppressWarnings("unchecked") private ChatTurnEngine engine(StreamingChatModel supplied, ChatTurnProperties properties) { ObjectProvider<StreamingChatModel> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(supplied); return new ChatTurnEngine(provider, new HistoryToolFactory(search, new ObjectMapper().findAndRegisterModules(), mock(com.fragpicker.digest.DigestReadService.class)), properties); }
    private ChatTurnResult stream(List<ConversationExchange> history, String question, TurnCancellation cancellation, ChatTurnListener listener) { return engine.stream(new CurrentUser(9L), history, question, cancellation, listener); }
    private void emit(StreamingChatResponseHandler callback, String... tokens) { for (String token : tokens) callback.onPartialResponse(new PartialResponse(token), new PartialResponseContext(handle)); }
    private void complete(StreamingChatResponseHandler callback, AiMessage message) { callback.onCompleteResponse(ChatResponse.builder().aiMessage(message).build()); }
    private ToolExecutionRequest call(String id) { return ToolExecutionRequest.builder().id(id).name(HistorySearchTool.NAME).arguments("{\"query\":\"导数\"}").build(); }
    private SearchResponse.Hit card() { return card(42); }
    private SearchResponse.Hit card(long id) { return new SearchResponse.Hit(id, "导数", "老师", LocalDate.of(2026,10,6), "摘要", List.of(), "/owned/video", "/owned/cover", .6, .6, false, new SearchResponse.Match(0, "TITLE", null, null, null, "导数")); }
    private void finalAnswerAfterSearch(String answer) {
        var index = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler callback = invocation.getArgument(1);
            if (index.getAndIncrement() == 0) complete(callback, AiMessage.from(call("search")));
            else { emit(callback, answer); complete(callback, AiMessage.from(answer)); }
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }
    private void assertCode(Runnable operation, String code) { assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code)); }
    private static class FakeHandle implements StreamingHandle { private volatile boolean cancelled; @Override public void cancel() { cancelled = true; } @Override public boolean isCancelled() { return cancelled; } }
}
