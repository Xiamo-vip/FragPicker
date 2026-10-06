package com.fragpicker.integration.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in, billable live checks. No personal library content or credentials are logged. */
@EnabledIfEnvironmentVariable(named = "AI_CHAT_TEST_ENABLED", matches = "true")
class ChatModelLiveIntegrationTest {
    private ChatModelProperties properties() {
        String baseUrl = System.getenv("AI_CHAT_BASE_URL");
        return new ChatModelProperties(true, baseUrl == null || baseUrl.isBlank()
                ? "https://api.deepseek.com" : baseUrl,
                System.getenv("AI_CHAT_API_KEY"), System.getenv("AI_CHAT_MODEL"),
                Duration.ofSeconds(90), 4096);
    }

    @Test
    void receivesAnActualChineseAnswer() {
        var model = new ChatModelConfiguration().chatModel(properties());
        var answer = liveCall(() -> model.chat("请只回复这六个汉字：碎片知识已保存"));
        assertThat(answer).contains("碎片知识已保存");
    }

    @Test
    void receivesActualStreamingTextAndOneCompletion() throws Exception {
        var complete = new CompletableFuture<ChatResponse>();
        var text = new StringBuffer();
        var completions = new AtomicInteger();
        var model = new ChatModelConfiguration().streamingChatModel(properties());
        liveCall(() -> {
            model.chat("请只回复这六个汉字：历史资源已找到", new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String token) { text.append(token); }
                @Override public void onCompleteResponse(ChatResponse response) {
                    completions.incrementAndGet();
                    complete.complete(response);
                }
                @Override public void onError(Throwable error) {
                    complete.completeExceptionally(new IllegalStateException(
                            "Live stream failed: " + error.getClass().getSimpleName()));
                }
            });
            return true;
        });
        var response = complete.get(100, TimeUnit.SECONDS);
        assertThat(text.toString()).contains("历史资源已找到");
        assertThat(response.aiMessage().text()).isEqualTo(text.toString());
        assertThat(completions.get()).isEqualTo(1);
    }

    @Test
    void callsToolAndResumesWithItsResult() throws Exception {
        var model = new ChatModelConfiguration().chatModel(properties());
        var tool = ToolSpecification.builder().name("searchFragments")
                .description("检索用户历史资源；查询参数保持用户输入的关键词")
                .parameters(JsonObjectSchema.builder().addStringProperty("query")
                        .required("query").build()).build();
        var instructions = SystemMessage.from("你在执行工具协议测试。用户要检索时必须先调用 searchFragments，"
                + "不能虚构结果；工具返回后，只输出结果的 title，不再次调用工具。");
        var question = UserMessage.from("请检索我的历史资源，关键词为：变化率");
        var first = liveCall(() -> model.chat(ChatRequest.builder()
                .messages(instructions, question).toolSpecifications(tool).build()));
        assertThat(first.aiMessage().toolExecutionRequests()).hasSize(1);
        var call = first.aiMessage().toolExecutionRequests().getFirst();
        assertThat(call.name()).isEqualTo("searchFragments");
        assertThat(new ObjectMapper().readTree(call.arguments()).path("query").asText())
                .isEqualTo("变化率");
        // Synthetic tool output: this validates the provider protocol, not DB retrieval.
        var result = ToolExecutionResultMessage.from(call,
                "[{\"fragmentId\":42,\"title\":\"导数测试资源42\"}]");
        var second = liveCall(() -> model.chat(ChatRequest.builder()
                .messages(instructions, question, first.aiMessage(), result)
                .toolSpecifications(tool).build()));
        assertThat(second.aiMessage().hasToolExecutionRequests()).isFalse();
        assertThat(second.aiMessage().text()).contains("导数测试资源42");
    }

    private static <T> T liveCall(Supplier<T> operation) {
        try { return operation.get(); }
        catch (RuntimeException error) {
            // SDK provider errors can contain raw response bodies; never retain the cause.
            throw new AssertionError("Live chat failed: " + error.getClass().getSimpleName());
        }
    }
}
