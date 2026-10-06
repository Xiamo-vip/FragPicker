package com.fragpicker.integration.parsevideo;

import java.net.URI;

public record ParsedVideo(String title, URI videoUrl, URI coverUrl, String authorName, String authorUid, URI authorAvatar) {
    @Override public String toString() { return "ParsedVideo[mediaUrls=REDACTED]"; }
}
