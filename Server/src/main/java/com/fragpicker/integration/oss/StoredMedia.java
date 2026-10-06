package com.fragpicker.integration.oss;

/** Persist the object key, not a presigned URL. */
public record StoredMedia(String bucket, String key, long sizeBytes, String sha256, String contentType) { }
