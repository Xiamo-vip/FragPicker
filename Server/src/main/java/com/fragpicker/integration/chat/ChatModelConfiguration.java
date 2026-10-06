package com.fragpicker.integration.chat;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "fragpicker.integrations.chat", name = "enabled", havingValue = "true")
public class ChatModelConfiguration {
    @Bean
    ChatModel chatModel(ChatModelProperties properties) {
        properties.validateEnabled();
        return OpenAiChatModel.builder()
                .baseUrl(properties.baseUrl()).apiKey(properties.apiKey()).modelName(properties.model())
                .timeout(properties.timeout()).maxTokens(properties.maxOutputTokens())
                .returnThinking(true).sendThinking(true)
                .maxRetries(0).logRequests(false).logResponses(false).build();
    }

    @Bean
    StreamingChatModel streamingChatModel(ChatModelProperties properties) {
        properties.validateEnabled();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(properties.baseUrl()).apiKey(properties.apiKey()).modelName(properties.model())
                .timeout(properties.timeout()).maxTokens(properties.maxOutputTokens())
                .returnThinking(true).sendThinking(true)
                .logRequests(false).logResponses(false).build();
    }
}
