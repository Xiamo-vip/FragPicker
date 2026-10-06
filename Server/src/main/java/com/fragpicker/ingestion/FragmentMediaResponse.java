package com.fragpicker.ingestion;

import com.fragpicker.integration.oss.MediaKind;
import java.net.URI;
import java.time.Instant;

public record FragmentMediaResponse(MediaKind kind, URI url, Instant expiresAt, long sizeBytes, String contentType) {
    @Override public String toString() { return "FragmentMediaResponse[kind=" + kind + ", url=REDACTED, expiresAt=" + expiresAt + "]"; }
}
