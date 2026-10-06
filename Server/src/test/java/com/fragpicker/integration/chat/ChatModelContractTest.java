package com.fragpicker.integration.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Local HTTP fixtures verify the wire contract; they do not certify a live vendor. */
class ChatModelContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private String responseBody;
    private String contentType = "application/json";
    private int status = 200;
    private long responseDelayMillis;

    @BeforeEach
    void startFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(json.readTree(exchange.getRequestBody()));
            if (responseDelayMillis > 0) {
                try { Thread.sleep(responseDelayMillis); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }

    @AfterEach
    void stopFixture() { server.stop(0); }

    private ChatModelProperties properties() {
        return new ChatModelProperties(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/custom/v1",
                "contract-test-key", "vendor-model", Duration.ofSeconds(3), 512);
    }

    @Test
    void usesConfiguredPathKeyModelAndOutputLimit() {
        responseBody = """
                {"id":"fixture","object":"chat.completion","created":1,"model":"vendor-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"可以查找你的导数资源。"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                """;
        var model = new ChatModelConfiguration().chatModel(properties());
        assertThat(model.chat("帮我找导数资源")).isEqualTo("可以查找你的导数资源。");
        assertThat(authorization.get()).isEqualTo("Bearer contract-test-key");
        assertThat(request.get().path("model").asText()).isEqualTo("vendor-model");
        assertThat(request.get().path("max_tokens").asInt()).isEqualTo(512);
        assertThat(request.get().path("messages").get(0).path("content").asText()).contains("导数");
    }

    @Test
    void preservesToolCallNameAndStructuredArguments() {
        responseBody = """
                {"id":"fixture","object":"chat.completion","created":1,"model":"vendor-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":null,"reasoning_content":"fixture reasoning",
                 "tool_calls":[{"id":"call_1","type":"function","function":{"name":"searchFragments","arguments":"{\\"query\\":\\"变化率\\"}"}}]},
                 "finish_reason":"tool_calls"}]}
                """;
        var tool = ToolSpecification.builder().name("searchFragments").description("检索当前用户的知识")
                .parameters(JsonObjectSchema.builder().addStringProperty("query").required("query").build()).build();
        var result = new ChatModelConfiguration().chatModel(properties()).chat(ChatRequest.builder()
                .messages(UserMessage.from("找一下变化率的资源")).toolSpecifications(tool).build());
        assertThat(result.aiMessage().toolExecutionRequests()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("searchFragments");
            assertThat(call.arguments()).isEqualTo("{\"query\":\"变化率\"}");
        });
        assertThat(request.get().path("tools").get(0).path("function").path("name").asText()).isEqualTo("searchFragments");
        assertThat(result.aiMessage().thinking()).isEqualTo("fixture reasoning");
        responseBody = """
                {"id":"fixture","choices":[{"index":0,"message":{"role":"assistant","content":"这是查询结果"},"finish_reason":"stop"}]}
                """;
        new ChatModelConfiguration().chatModel(properties()).chat(ChatRequest.builder()
                .messages(UserMessage.from("查找资源"), result.aiMessage(),
                        ToolExecutionResultMessage.from(result.aiMessage().toolExecutionRequests().getFirst(), "[]"))
                .toolSpecifications(tool).build());
        assertThat(request.get().path("messages").get(1).path("reasoning_content").asText()).isEqualTo("fixture reasoning");
    }

    @Test
    void streamsTextAndCompletesOnce() throws Exception {
        contentType = "text/event-stream";
        responseBody = """
                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"vendor-model","choices":[{"index":0,"delta":{"role":"assistant","content":"找到"},"finish_reason":null}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"vendor-model","choices":[{"index":0,"delta":{"content":"导数资源"},"finish_reason":null}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"vendor-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        var result = new CompletableFuture<ChatResponse>();
        var parts = new StringBuffer();
        var completions = new AtomicInteger();
        new ChatModelConfiguration().streamingChatModel(properties()).chat("查找资源", new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String text) { parts.append(text); }
            @Override public void onCompleteResponse(ChatResponse response) { completions.incrementAndGet(); result.complete(response); }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        });
        assertThat(result.get(5, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("找到导数资源");
        assertThat(parts.toString()).isEqualTo("找到导数资源");
        assertThat(completions.get()).isEqualTo(1);
        assertThat(request.get().path("stream").asBoolean()).isTrue();
    }

    @Test
    void propagatesProviderFailureWithoutAutomaticBillableRetries() {
        status = 429;
        responseBody = "{\"error\":{\"message\":\"rate limited\",\"type\":\"rate_limit_error\"}}";
        assertThatThrownBy(() -> new ChatModelConfiguration().chatModel(properties()).chat("查找资源"))
                .isInstanceOf(RuntimeException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void reportsStreamingProviderFailureToCallback() {
        status = 401;
        responseBody = "{\"error\":{\"message\":\"invalid key\",\"type\":\"authentication_error\"}}";
        var result = new CompletableFuture<ChatResponse>();
        new ChatModelConfiguration().streamingChatModel(properties()).chat("查找资源", new StreamingChatResponseHandler() {
            @Override public void onCompleteResponse(ChatResponse response) { result.complete(response); }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        });
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void enforcesConfiguredTimeout() {
        responseDelayMillis = 1500;
        responseBody = "{\"choices\":[]}";
        var p = properties();
        var shortTimeout = new ChatModelProperties(true, p.baseUrl(), p.apiKey(), p.model(), Duration.ofSeconds(1), 512);
        assertThatThrownBy(() -> new ChatModelConfiguration().chatModel(shortTimeout).chat("查找资源"))
                .isInstanceOf(RuntimeException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
}
