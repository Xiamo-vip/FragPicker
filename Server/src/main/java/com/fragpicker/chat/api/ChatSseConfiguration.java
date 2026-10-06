package com.fragpicker.chat.api;
import com.fragpicker.chat.turn.ChatTurnProperties;
import org.springframework.context.annotation.*;
import java.util.concurrent.*;
@Configuration(proxyBeanMethods = false)
@Profile("database")
public class ChatSseConfiguration {
    @Bean(name = "chatSseExecutor", destroyMethod = "shutdownNow")
    ThreadPoolExecutor executor(ChatTurnProperties properties) {
        return new ThreadPoolExecutor(properties.maxConcurrent(), properties.maxConcurrent(), 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().name("fragpicker-chat-sse-", 0).daemon(true).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
}
