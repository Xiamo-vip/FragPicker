package com.fragpicker.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.integration.chat.ChatModelProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.slf4j.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DigestWorker {
    private static final Logger log=LoggerFactory.getLogger(DigestWorker.class);
    private final DigestStore store;
    private final DailyDigestGenerator generator;
    private final ChatModel model;
    private final ChatModelProperties provider;
    private final ObjectMapper json;
    private final AtomicBoolean busy=new AtomicBoolean();
    public DigestWorker(DigestStore store, DailyDigestGenerator generator, ChatModel model, ChatModelProperties provider, ObjectMapper json) {
        this.store=store; this.generator=generator; this.model=model; this.provider=provider; this.json=json;
    }
    @Scheduled(fixedDelayString="${fragpicker.digest.worker.poll-delay:2s}")
    public void tick() { processNext(); }
    public boolean processNext() {
        if (!busy.compareAndSet(false,true)) return false;
        try {
            var claim=store.claim(); if (claim.isEmpty()) return false;
            var lease=claim.get();
            try {
                store.snapshot(lease); var calls=new DurableCalls(lease);
                var result=generator.generate(lease.userId(),lease.date(),new Sources(lease),() -> false,calls::checkpoint,calls::request);
                if (!store.complete(lease,result)) log.info("Digest {} stopped after lease replacement",lease.id());
            } catch (ApiException stable) { store.fail(lease,stable.code()); log.warn("Digest {} stopped ({})",lease.id(),stable.code()); }
            catch (RuntimeException unavailable) { store.fail(lease,"DIGEST_SOURCE_UNAVAILABLE"); log.warn("Digest {} stopped (DIGEST_SOURCE_UNAVAILABLE)",lease.id()); }
            return true;
        } catch (RuntimeException unavailable) { log.warn("Digest queue unavailable"); return false; }
        finally { busy.set(false); }
    }
    private final class DurableCalls {
        private final DigestLease lease;
        private String lastHash;
        private boolean reused;
        DurableCalls(DigestLease lease) { this.lease=lease; }
        ChatResponse request(ChatRequest request) {
            if (!store.renew(lease)) throw cancelled();
            lastHash=DigestRequestFingerprint.hash(request,provider,json);
            var cached=store.cached(lease,lastHash); reused=cached.isPresent();
            if (reused) {
                var piece=cached.get();
                try {
                    String body=json.writeValueAsString(Map.of("summary",piece.summary(),"points",piece.points(),"categories",piece.categories(),"keywords",piece.keywords()));
                    return ChatResponse.builder().aiMessage(AiMessage.from(body)).finishReason(FinishReason.STOP).build();
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Cannot encode digest checkpoint"); }
            }
            if (!store.beginCall(lease,lastHash)) throw cancelled();
            var answer=model.chat(request);
            if (!store.renew(lease)) throw cancelled();
            return answer;
        }
        void checkpoint(DigestPiece piece) {
            if (lastHash == null || !(reused ? store.renew(lease) : store.checkpoint(lease,lastHash,piece))) throw cancelled();
            lastHash=null;
        }
    }
    private final class Sources implements Iterator<DigestSource> {
        private final DigestLease lease;
        private List<DigestSource> page=List.of();
        private int position;
        private Long before;
        private boolean end;
        Sources(DigestLease lease) { this.lease=lease; }
        @Override public boolean hasNext() {
            if (position < page.size()) return true; if (end) return false;
            page=store.sources(lease,before); position=0; end=page.isEmpty();
            if (!end) before=page.getLast().fragmentId(); return !end;
        }
        @Override public DigestSource next() { if (!hasNext()) throw new NoSuchElementException(); return page.get(position++); }
    }
    private static ApiException cancelled() { return new ApiException(HttpStatus.CONFLICT,"DIGEST_CANCELLED","总结任务已切换，请刷新查看"); }
}
