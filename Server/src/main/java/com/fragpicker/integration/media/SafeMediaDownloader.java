package com.fragpicker.integration.media;

import com.fragpicker.integration.oss.MediaKind;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.fragpicker.integration.media.MediaDownloadFailure.Code.*;

public class SafeMediaDownloader implements AutoCloseable {
    private final CloseableHttpClient client;
    private final MediaDownloadProperties properties;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "media-download-deadline"); thread.setDaemon(true); return thread;
    });

    public SafeMediaDownloader(CloseableHttpClient client, MediaDownloadProperties properties) {
        properties.validate(); this.client = client; this.properties = properties;
    }

    public DownloadedMedia download(URI resource, URI source, MediaKind kind) {
        PublicNetworkPolicy.validateUri(resource); PublicNetworkPolicy.validateUri(source);
        if (kind == null) throw new IllegalArgumentException("Media kind required");
        var current = new AtomicReference<HttpGet>();
        var timedOut = new AtomicBoolean(false);
        var deadline = deadlines.schedule(() -> {
            timedOut.set(true); var request = current.get(); if (request != null) request.abort();
        }, properties.totalTimeout().toMillis(), TimeUnit.MILLISECONDS);
        Path temporary = null;
        try {
            URI target = resource;
            long limit = kind == MediaKind.VIDEO ? properties.maxVideoBytes() : properties.maxCoverBytes();
            for (int hop = 0; ; hop++) {
                PublicNetworkPolicy.validateUri(target);
                if (timedOut.get()) throw new MediaDownloadFailure(TIMEOUT, true);
                var request = new HttpGet(target);
                request.setHeader("Accept-Encoding", "identity");
                // Send only the original platform origin, not user notes, credentials or query tokens.
                request.setHeader("Referer", source.getScheme() + "://" + source.getRawAuthority() + "/");
                current.set(request);
                if (timedOut.get()) { request.abort(); throw new MediaDownloadFailure(TIMEOUT, true); }
                try (var response = client.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                        if (hop >= properties.maxRedirects()) throw new MediaDownloadFailure(REDIRECT_LIMIT, false);
                        var location = response.getFirstHeader("Location");
                        if (location == null || location.getValue().length() > 8192) throw new MediaDownloadFailure(TARGET_REJECTED, false);
                        URI next;
                        try { next = target.resolve(location.getValue()); }
                        catch (IllegalArgumentException invalid) { throw new MediaDownloadFailure(TARGET_REJECTED, false); }
                        if ("https".equals(target.getScheme()) && !"https".equals(next.getScheme())) throw new MediaDownloadFailure(TARGET_REJECTED, false);
                        target = next; continue;
                    }
                    if (status == 401 || status == 403) throw new MediaDownloadFailure(SOURCE_EXPIRED, true);
                    if (status != 200) throw new MediaDownloadFailure(SOURCE_REJECTED, status == 429 || status >= 500);
                    var entity = response.getEntity();
                    if (entity == null) throw new MediaDownloadFailure(UNSUPPORTED_CONTENT, false);
                    var encoding = entity.getContentEncoding();
                    if (encoding != null && !"identity".equalsIgnoreCase(encoding.getValue())) throw new MediaDownloadFailure(UNSUPPORTED_CONTENT, false);
                    long advertised = entity.getContentLength();
                    if (advertised > limit) throw new MediaDownloadFailure(FILE_TOO_LARGE, false);
                    Files.createDirectories(properties.tempDirectory());
                    temporary = Files.createTempFile(properties.tempDirectory(), "fragpicker-", ".media");
                    long bytes = 0;
                    String contentType;
                    try (var input = entity.getContent(); var output = Files.newOutputStream(temporary)) {
                        try {
                            byte[] head = input.readNBytes((int) Math.min(32, limit + 1));
                            bytes = head.length;
                            if (bytes > limit) throw new MediaDownloadFailure(FILE_TOO_LARGE, false);
                            contentType = MediaSniffer.detect(head, kind);
                            output.write(head);
                            byte[] buffer = new byte[65536]; int count;
                            while ((count = input.read(buffer)) != -1) {
                                if (timedOut.get()) throw new MediaDownloadFailure(TIMEOUT, true);
                                bytes += count;
                                if (bytes > limit) throw new MediaDownloadFailure(FILE_TOO_LARGE, false);
                                output.write(buffer, 0, count);
                            }
                        } catch (IOException | RuntimeException failure) {
                            // Abort before closing the entity stream; closing alone may drain an oversized response.
                            request.abort(); throw failure;
                        }
                    }
                    if (timedOut.get()) throw new MediaDownloadFailure(TIMEOUT, true);
                    if (bytes == 0 || (advertised >= 0 && bytes != advertised)) throw new MediaDownloadFailure(NETWORK_ERROR, true);
                    return new DownloadedMedia(temporary, bytes, contentType);
                }
            }
        } catch (MediaDownloadFailure failure) { cleanup(temporary); throw failure; }
        catch (IOException failure) {
            cleanup(temporary);
            if (failure instanceof java.nio.file.FileSystemException) throw new MediaDownloadFailure(LOCAL_FILE_ERROR, false);
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof PublicNetworkPolicy.RejectedAddress) throw new MediaDownloadFailure(TARGET_REJECTED, false);
                if (cause instanceof java.net.SocketTimeoutException || cause instanceof org.apache.http.conn.ConnectTimeoutException) throw new MediaDownloadFailure(TIMEOUT, true);
                cause = cause.getCause();
            }
            throw new MediaDownloadFailure(timedOut.get() ? TIMEOUT : NETWORK_ERROR, true);
        } finally {
            deadline.cancel(false); current.set(null);
        }
    }

    private void cleanup(Path file) {
        if (file == null) return;
        try { Files.deleteIfExists(file); }
        catch (IOException failure) { throw new MediaDownloadFailure(LOCAL_FILE_ERROR, false); }
    }

    @Override public void close() throws IOException { deadlines.shutdownNow(); client.close(); }
}
