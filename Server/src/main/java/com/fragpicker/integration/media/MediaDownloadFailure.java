package com.fragpicker.integration.media;

public class MediaDownloadFailure extends RuntimeException {
    public enum Code { TARGET_REJECTED, REDIRECT_LIMIT, FILE_TOO_LARGE, UNSUPPORTED_CONTENT, SOURCE_REJECTED, SOURCE_EXPIRED, NETWORK_ERROR, TIMEOUT, LOCAL_FILE_ERROR }
    private final Code code;
    private final boolean retryable;
    public MediaDownloadFailure(Code code, boolean retryable) {
        super("Media download failed: " + code);
        this.code = code; this.retryable = retryable;
    }
    public Code code() { return code; }
    public boolean retryable() { return retryable; }
}
