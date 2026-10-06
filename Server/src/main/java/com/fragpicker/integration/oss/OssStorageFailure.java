package com.fragpicker.integration.oss;

public class OssStorageFailure extends RuntimeException {
    public enum Code { BUCKET_NOT_PRIVATE, ACCESS_DENIED, NOT_FOUND, INTEGRITY_ERROR, UNAVAILABLE, PROVIDER_REJECTED, LOCAL_FILE_ERROR, FILE_TOO_LARGE }
    private final Code code;
    private final boolean retryable;
    public OssStorageFailure(Code code, boolean retryable) {
        super("OSS storage failed: " + code);
        this.code = code; this.retryable = retryable;
    }
    public Code code() { return code; }
    public boolean retryable() { return retryable; }
}
