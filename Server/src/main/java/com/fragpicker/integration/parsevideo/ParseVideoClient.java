package com.fragpicker.integration.parsevideo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static com.fragpicker.integration.parsevideo.ParseVideoFailure.Code.*;

/** Maps the deployed code/msg/data contract; returns metadata without downloading media. */
public class ParseVideoClient {
    private final RestClient http;
    private final ParseVideoProperties properties;
    private final ObjectMapper json;

    public ParseVideoClient(RestClient http, ParseVideoProperties properties, ObjectMapper json) {
        this.http = http; this.properties = properties; this.json = json;
    }

    public ParsedVideo parse(String shareLink) {
        mediaUri(shareLink, true, INVALID_LINK);
        URI uri = URI.create(properties.baseUrl().replaceAll("/+$", "") + "/video/share/url/parse?url="
                + URLEncoder.encode(shareLink, StandardCharsets.UTF_8));
        try {
            return http.get().uri(uri).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    int status = response.getStatusCode().value();
                    throw new ParseVideoFailure(PROVIDER_UNAVAILABLE, status == 429 || status >= 500);
                }
                if (response.getHeaders().getContentLength() > properties.maxResponseBytes()) throw new ParseVideoFailure(RESPONSE_TOO_LARGE, false);
                byte[] body = response.getBody().readNBytes(properties.maxResponseBytes() + 1);
                if (body.length > properties.maxResponseBytes()) throw new ParseVideoFailure(RESPONSE_TOO_LARGE, false);
                JsonNode envelope = json.readTree(body);
                if (envelope == null || !envelope.path("code").isIntegralNumber()) throw new ParseVideoFailure(INVALID_RESPONSE, false);
                int code = envelope.path("code").asInt();
                if (code != 200) throw new ParseVideoFailure(code == 400 ? INVALID_LINK : PROVIDER_UNAVAILABLE, code != 400);
                JsonNode data = envelope.path("data");
                if (!data.isObject()) throw new ParseVideoFailure(INVALID_RESPONSE, false);
                String video = text(data, "video_url");
                if (video == null) throw new ParseVideoFailure(UNSUPPORTED_CONTENT, false);
                JsonNode author = data.path("author");
                return new ParsedVideo(text(data, "title"), mediaUri(video, true, INVALID_RESPONSE),
                        mediaUri(text(data, "cover_url"), false, INVALID_RESPONSE), text(author, "name"),
                        author.path("uid").isIntegralNumber() ? author.path("uid").asText() : text(author, "uid"),
                        mediaUri(text(author, "avatar"), false, INVALID_RESPONSE));
            });
        } catch (ParseVideoFailure failure) { throw failure; }
        catch (Exception failure) {
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof java.net.SocketTimeoutException) throw new ParseVideoFailure(TIMEOUT, true);
                if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException) throw new ParseVideoFailure(INVALID_RESPONSE, false);
                cause = cause.getCause();
            }
            throw new ParseVideoFailure(PROVIDER_UNAVAILABLE, true);
        }
    }

    private String text(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw new ParseVideoFailure(INVALID_RESPONSE, false);
        String text = value.asText().strip();
        if (text.length() > 8192) throw new ParseVideoFailure(INVALID_RESPONSE, false);
        return text.isEmpty() ? null : text;
    }

    private URI mediaUri(String value, boolean required, ParseVideoFailure.Code failureCode) {
        if (value == null || value.isBlank()) {
            if (required) throw new ParseVideoFailure(failureCode, false);
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme() == null ? "" : uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null || value.length() > 8192) {
                throw new ParseVideoFailure(failureCode, false);
            }
            return uri;
        } catch (IllegalArgumentException error) { throw new ParseVideoFailure(failureCode, false); }
    }
}
