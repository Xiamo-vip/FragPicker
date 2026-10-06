package com.fragpicker.ingestion;

import com.fragpicker.integration.parsevideo.ParsedVideo;
import java.net.URI;

public record VideoMetadata(long fragmentId, long userId, String title, String videoUrl, String coverUrl,
                            String authorName, String authorUid, String authorAvatar) {
    static VideoMetadata from(ParseLease lease, ParsedVideo video) {
        return new VideoMetadata(lease.fragmentId(), lease.userId(), video.title(), video.videoUrl().toASCIIString(),
                url(video.coverUrl()), video.authorName(), video.authorUid(), url(video.authorAvatar()));
    }
    private static String url(URI uri) { return uri == null ? null : uri.toASCIIString(); }
    @Override public String toString() { return "VideoMetadata[fragmentId=" + fragmentId + ", mediaUrls=REDACTED]"; }
}
