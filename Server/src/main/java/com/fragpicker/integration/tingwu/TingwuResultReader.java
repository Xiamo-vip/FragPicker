package com.fragpicker.integration.tingwu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.fragpicker.integration.tingwu.TingwuFailure.Code.*;

/** Downloads only vendor OSS result documents, never sends cloud Authorization headers. */
public class TingwuResultReader implements AutoCloseable {
    private final CloseableHttpClient http;
    private final TingwuProperties properties;
    private final ObjectMapper json;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "tingwu-result-deadline"); thread.setDaemon(true); return thread;
    });
    public TingwuResultReader(CloseableHttpClient http, TingwuProperties properties, ObjectMapper json) {
        properties.validate(); this.http = http; this.properties = properties; this.json = json;
    }

    public TingwuResult read(TingwuTask task) {
        if (task.status() != TingwuTask.Status.COMPLETED) throw new TingwuFailure(TASK_NOT_COMPLETED, false);
        if (task.transcriptionUrl() == null) throw new TingwuFailure(INVALID_RESPONSE, false);
        var transcript = document(task.transcriptionUrl(), task.id()).path("Transcription");
        if (!transcript.isObject()) throw new TingwuFailure(INVALID_RESPONSE, false);
        var sentences = sentences(transcript);
        long duration = transcript.path("AudioInfo").has("Duration") ? number(transcript.path("AudioInfo"), "Duration") : 0;
        String summary = null;
        if (task.summaryUrl() != null) {
            var object = document(task.summaryUrl(), task.id()).path("Summarization");
            if (!object.isObject()) throw new TingwuFailure(INVALID_RESPONSE, false);
            if (object.hasNonNull("ParagraphSummary")) summary = text(object, "ParagraphSummary");
        }
        var keywords = new ArrayList<String>();
        var points = new ArrayList<TingwuResult.KeyPoint>();
        if (task.assistanceUrl() != null) {
            var assistance = document(task.assistanceUrl(), task.id()).path("MeetingAssistance");
            if (!assistance.isObject()) throw new TingwuFailure(INVALID_RESPONSE, false);
            if (assistance.has("Keywords")) {
                for (var word : array(assistance, "Keywords")) {
                    if (!word.isTextual()) throw new TingwuFailure(INVALID_RESPONSE, false);
                    keywords.add(word.asText());
                }
            }
            if (assistance.has("KeySentences")) {
                for (var point : array(assistance, "KeySentences")) {
                    long start = number(point, "Start"), end = number(point, "End"); ordered(start, end);
                    points.add(new TingwuResult.KeyPoint(number(point, "SentenceId"), start, end, text(point, "Text")));
                }
            }
        }
        return new TingwuResult(task.id(), duration, sentences, summary, keywords, points);
    }

    private List<TingwuResult.Sentence> sentences(JsonNode transcript) {
        var result = new ArrayList<TingwuResult.Sentence>();
        for (var paragraph : array(transcript, "Paragraphs")) {
            String paragraphId = scalarId(paragraph, "ParagraphId");
            String speakerId = paragraph.hasNonNull("SpeakerId") ? scalarId(paragraph, "SpeakerId") : null;
            long current = -1, start = 0, end = 0; var text = new StringBuilder();
            for (var word : array(paragraph, "Words")) {
                long id = number(word, "SentenceId"), wordStart = number(word, "Start"), wordEnd = number(word, "End");
                ordered(wordStart, wordEnd);
                if (current != -1 && current != id) {
                    result.add(new TingwuResult.Sentence(paragraphId, speakerId, current, start, end, text.toString()));
                    text.setLength(0);
                }
                if (current != id) { start = wordStart; end = wordEnd; }
                else { start = Math.min(start, wordStart); end = Math.max(end, wordEnd); }
                current = id; text.append(text(word, "Text"));
            }
            if (current != -1) result.add(new TingwuResult.Sentence(paragraphId, speakerId, current, start, end, text.toString()));
        }
        return result;
    }

    private JsonNode document(URI uri, String taskId) {
        URI resource = TingwuUrls.requirePublicOss(uri.toASCIIString());
        var request = new HttpGet(resource); request.setHeader("Accept-Encoding", "identity");
        var expired = new AtomicBoolean();
        var timeout = deadlines.schedule(() -> { expired.set(true); request.abort(); }, properties.resultTimeout().toMillis(), TimeUnit.MILLISECONDS);
        try (var response = http.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            if (status == 403) throw new TingwuFailure(RESULT_EXPIRED, true);
            if (status != 200) throw new TingwuFailure(UNAVAILABLE, status == 429 || status >= 500);
            var entity = response.getEntity();
            if (entity == null || (entity.getContentEncoding() != null && !"identity".equalsIgnoreCase(entity.getContentEncoding().getValue()))) {
                throw new TingwuFailure(INVALID_RESPONSE, false);
            }
            if (entity.getContentLength() > properties.maxResultBytes()) throw new TingwuFailure(RESPONSE_TOO_LARGE, false);
            byte[] bytes;
            try (var input = entity.getContent()) {
                try {
                    bytes = input.readNBytes(properties.maxResultBytes() + 1);
                    if (bytes.length > properties.maxResultBytes()) throw new TingwuFailure(RESPONSE_TOO_LARGE, false);
                } catch (IOException | RuntimeException failure) { request.abort(); throw failure; }
            }
            if (expired.get()) throw new TingwuFailure(TIMEOUT, true);
            var node = json.readTree(bytes);
            if (node == null || !node.isObject() || !taskId.equals(text(node, "TaskId"))) throw new TingwuFailure(INVALID_RESPONSE, false);
            return node;
        } catch (TingwuFailure failure) { throw failure; }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new TingwuFailure(INVALID_RESPONSE, false); }
        catch (IOException failure) {
            throw new TingwuFailure(expired.get() || failure instanceof java.net.SocketTimeoutException ? TIMEOUT : NETWORK_ERROR, true);
        } finally { timeout.cancel(false); request.abort(); }
    }
    private JsonNode array(JsonNode object, String field) {
        var value = object.path(field); if (!value.isArray()) throw new TingwuFailure(INVALID_RESPONSE, false); return value;
    }
    private long number(JsonNode object, String field) {
        var value = object.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) throw new TingwuFailure(INVALID_RESPONSE, false);
        return value.asLong();
    }
    private String text(JsonNode object, String field) {
        var value = object.path(field); if (!value.isTextual()) throw new TingwuFailure(INVALID_RESPONSE, false); return value.asText();
    }
    private String scalarId(JsonNode object, String field) {
        var value = object.path(field);
        if (!(value.isTextual() || value.isIntegralNumber())) throw new TingwuFailure(INVALID_RESPONSE, false);
        return value.asText();
    }
    private void ordered(long start, long end) { if (end < start) throw new TingwuFailure(INVALID_RESPONSE, false); }
    @Override public void close() throws IOException { deadlines.shutdownNow(); http.close(); }
}
