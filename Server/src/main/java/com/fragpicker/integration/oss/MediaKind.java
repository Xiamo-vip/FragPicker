package com.fragpicker.integration.oss;

import java.util.Set;

public enum MediaKind {
    VIDEO("video", Set.of("video/mp4", "video/webm", "video/quicktime", "video/x-flv", "video/x-matroska", "application/octet-stream")),
    COVER("cover", Set.of("image/jpeg", "image/png", "image/webp", "image/gif"));

    private final String segment;
    private final Set<String> contentTypes;
    MediaKind(String segment, Set<String> contentTypes) { this.segment = segment; this.contentTypes = contentTypes; }
    public String segment() { return segment; }
    public boolean allows(String contentType) { return contentTypes.contains(contentType); }
}
