package com.fragpicker.ingestion;

import com.fragpicker.integration.oss.MediaKind;
import com.fragpicker.integration.oss.StoredMedia;

public record StoredMediaRecord(long fragmentId, long userId, MediaKind kind, String bucket,
                                String objectKey, long sizeBytes, String sha256, String contentType) {
    static StoredMediaRecord from(MediaLease lease, MediaKind kind, StoredMedia media) {
        String expected = "users/" + lease.userId() + "/fragments/" + lease.fragmentId()
                + "/" + kind.segment() + "/" + media.sha256();
        if (!expected.equals(media.key()) || !media.sha256().matches("[a-f0-9]{64}")
                || media.sizeBytes() <= 0 || !kind.allows(media.contentType())) {
            throw new IllegalArgumentException("Stored media does not match task owner or kind");
        }
        return new StoredMediaRecord(lease.fragmentId(), lease.userId(), kind, media.bucket(),
                media.key(), media.sizeBytes(), media.sha256(), media.contentType());
    }
}
