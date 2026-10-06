package com.fragpicker.ingestion;

import com.fragpicker.integration.media.*;
import com.fragpicker.integration.oss.*;
import com.fragpicker.integration.parsevideo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import java.net.URI;

public class MediaStorageWorker {
    private static final Logger log = LoggerFactory.getLogger(MediaStorageWorker.class);
    private final MediaJobStore store;
    private final SafeMediaDownloader downloader;
    private final OssMediaStorage storage;
    private final ParseVideoClient parser;

    public MediaStorageWorker(MediaJobStore store, SafeMediaDownloader downloader, OssMediaStorage storage, ParseVideoClient parser) {
        this.store = store; this.downloader = downloader; this.storage = storage; this.parser = parser;
    }
    @Scheduled(fixedDelayString = "${fragpicker.ingestion.media-worker.poll-delay}",
            initialDelayString = "${fragpicker.ingestion.media-worker.poll-delay}")
    public void poll() {
        try { runOnce(); }
        catch (Exception failure) { log.warn("Media poll failed; leases remain recoverable ({})", failure.getClass().getSimpleName()); }
    }
    public boolean runOnce() {
        var claimed = store.claim();
        if (claimed.isEmpty()) return false;
        var lease = claimed.get();
        try {
            // A source may have expired since parsing. Refresh at most once within this attempt.
            try { saveMissing(lease); }
            catch (MediaDownloadFailure failure) {
                if (failure.code() != MediaDownloadFailure.Code.SOURCE_EXPIRED) throw failure;
                if (!store.refreshMetadata(lease, parser.parse(lease.sourceUrl()))) return true;
                saveMissing(lease);
            }
            store.complete(lease);
        } catch (StaleLease ignored) {
            // Uploaded objects have content-addressed keys. Do not delete a key another lease may use.
        } catch (MediaDownloadFailure failure) { store.fail(lease, "MEDIA_" + failure.code().name(), failure.retryable()); }
        catch (OssStorageFailure failure) { store.fail(lease, "OSS_" + failure.code().name(), failure.retryable()); }
        catch (ParseVideoFailure failure) { store.fail(lease, "MEDIA_PARSE_" + failure.code().name(), failure.retryable()); }
        catch (Exception failure) {
            log.warn("Media job {} failed internally ({})", lease.jobId(), failure.getClass().getSimpleName());
            store.fail(lease, "MEDIA_INTERNAL_ERROR", true);
        }
        return true;
    }
    private void saveMissing(MediaLease lease) throws java.io.IOException {
        var parsed = store.metadata(lease);
        if (!store.isStored(lease, MediaKind.VIDEO)) save(lease, MediaKind.VIDEO, parsed.videoUrl());
        if (parsed.coverUrl() != null && !store.isStored(lease, MediaKind.COVER)) save(lease, MediaKind.COVER, parsed.coverUrl());
    }
    private void save(MediaLease lease, MediaKind kind, String url) throws java.io.IOException {
        // Network and filesystem I/O are outside database transactions. Each successful upload commits separately.
        try (var downloaded = downloader.download(URI.create(url), URI.create(lease.sourceUrl()), kind)) {
            var uploaded = storage.put(lease.userId(), lease.fragmentId(), kind, downloaded.file(), downloaded.contentType());
            if (!store.checkpoint(lease, kind, uploaded)) throw new StaleLease();
        }
    }
    private static class StaleLease extends RuntimeException { }
}
