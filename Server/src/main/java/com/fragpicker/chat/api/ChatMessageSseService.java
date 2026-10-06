package com.fragpicker.chat.api;
import com.fragpicker.auth.CurrentUser;
import com.fragpicker.chat.persistence.*;
import com.fragpicker.chat.turn.*;
import com.fragpicker.common.api.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
@Service
@Profile("database")
public class ChatMessageSseService {
    private final ChatTurnStore store;
    private final ChatTurnEngine engine;
    private final ObjectProvider<StreamingChatModel> models;
    private final ThreadPoolExecutor executor;
    private final ChatTurnProperties properties;
    public ChatMessageSseService(ChatTurnStore store, ChatTurnEngine engine, ObjectProvider<StreamingChatModel> models,
            @Qualifier("chatSseExecutor") ThreadPoolExecutor executor, ChatTurnProperties properties) {
        this.store = store; this.engine = engine; this.models = models; this.executor = executor; this.properties = properties;
    }
    public SseEmitter send(CurrentUser user, long session, String key, SendChatMessage input) {
        var start = store.begin(user.id(), session, key, input == null ? null : input.message()); var ticket = start.turn();
        if (start.fresh() && models.getIfAvailable() == null) { safeFail(ticket, "CHAT_DISABLED"); throw error(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_DISABLED"); }
        var emitter = new SseEmitter(properties.timeout().plusSeconds(15).toMillis()); var cancellation = new TurnCancellation(); var channel = new Channel(emitter, cancellation, ticket.id());
        emitter.onCompletion(channel::disconnected); emitter.onError(ignored -> channel.disconnected()); emitter.onTimeout(() -> { channel.disconnected(); emitter.complete(); });
        try { executor.execute(() -> run(user, start, channel, cancellation)); }
        catch (RejectedExecutionException busy) { if (start.fresh()) safeFail(ticket, "CHAT_BUSY"); channel.disconnected(); throw error(HttpStatus.TOO_MANY_REQUESTS, "CHAT_BUSY"); }
        return emitter;
    }
    private void run(CurrentUser user, ChatTurnStore.Start start, Channel channel, TurnCancellation cancellation) {
        var ticket = start.turn();
        try {
            channel.event("accepted", Map.of("turnId", ticket.id(), "sessionId", ticket.sessionId(), "state", ticket.state(), "replayed", !start.fresh()));
            if (!start.fresh()) {
                var saved = store.get(user.id(), ticket.sessionId(), ticket.id());
                channel.event(saved.state().equals("COMPLETED") ? "done" : saved.state().equals("FAILED") ? "failed" : "pending", saved); return;
            }
            var result = engine.stream(user, store.context(ticket), ticket.question(), cancellation, new ChatTurnListener() {
                @Override public void roundStarted(int round) { channel.event("round_start", Map.of("round", round)); }
                @Override public void delta(int round, String text) { channel.event("delta", new TextDelta(round, text)); }
                @Override public void roundEnded(int round, boolean intermediate) { channel.event("round_end", Map.of("round", round, "intermediate", intermediate)); }
                @Override public void heartbeat() { channel.event("heartbeat", Map.of("turnId", ticket.id())); }
            });
            if (cancellation.isCancelled()) throw error(HttpStatus.REQUEST_TIMEOUT, "CHAT_CANCELLED");
            if (!store.complete(ticket, result)) throw error(HttpStatus.CONFLICT, "CHAT_INTERRUPTED");
            channel.event("done", store.get(user.id(), ticket.sessionId(), ticket.id()));
        } catch (ApiException stable) { if (start.fresh()) safeFail(ticket, stable.code()); channel.error(stable.code()); }
        catch (RuntimeException internal) { if (start.fresh()) safeFail(ticket, "CHAT_UNAVAILABLE"); channel.error("CHAT_UNAVAILABLE"); }
        finally { channel.finish(); }
    }
    private void safeFail(StoredChatTurn ticket, String code) {
        boolean interrupted = Thread.interrupted();
        try { store.fail(ticket, code); } catch (RuntimeException unavailable) { /* A later read/request expires the durable lease. */ }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private ApiException error(HttpStatus status, String code) { return new ApiException(status, code, "请稍后重试或读取已保存的消息状态"); }
    private record TextDelta(int round, String text) { @Override public String toString() { return "TextDelta[content=REDACTED]"; } }
    private static final class Channel {
        final SseEmitter emitter; final TurnCancellation cancellation; final long turn; final AtomicBoolean closed = new AtomicBoolean(); int sequence;
        Channel(SseEmitter emitter, TurnCancellation cancellation, long turn) { this.emitter = emitter; this.cancellation = cancellation; this.turn = turn; }
        void event(String name, Object data) {
            if (closed.get() || cancellation.isCancelled()) throw new ApiException(HttpStatus.REQUEST_TIMEOUT, "CHAT_CANCELLED", "连接已结束");
            try { emitter.send(SseEmitter.event().id(turn + ":" + ++sequence).name(name).data(data, MediaType.APPLICATION_JSON)); }
            catch (IOException disconnected) { disconnected(); throw new UncheckedIOException(disconnected); }
            catch (IllegalStateException disconnected) { disconnected(); throw new ApiException(HttpStatus.REQUEST_TIMEOUT, "CHAT_CANCELLED", "连接已结束"); }
        }
        void error(String code) { if (!closed.get()) try { event("error", ApiError.of(code, "对话未完成，请读取消息状态或重新连接")); } catch (RuntimeException disconnected) { } }
        void disconnected() { closed.set(true); cancellation.cancel(); }
        void finish() { if (closed.compareAndSet(false, true)) emitter.complete(); }
    }
}
