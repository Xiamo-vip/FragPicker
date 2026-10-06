package com.fragpicker.integration.oss;

import java.net.URI;
import java.time.Instant;

public record SignedMediaUrl(URI url, Instant expiresAt) {
    @Override public String toString() { return "SignedMediaUrl[url=REDACTED, expiresAt=" + expiresAt + "]"; }
}
