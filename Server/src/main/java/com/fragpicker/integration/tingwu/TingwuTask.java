package com.fragpicker.integration.tingwu;

import java.net.URI;

public record TingwuTask(String id, String taskKey, Status status, FailureCategory failure,
                         URI transcriptionUrl, URI summaryUrl, URI assistanceUrl) {
    public enum Status { ONGOING, COMPLETED, FAILED, INVALID }
    public enum FailureCategory { SOURCE_INVALID, UNSUPPORTED_MEDIA, OTHER }
    @Override public String toString() { return "TingwuTask[status=" + status + ", sensitiveFields=REDACTED]"; }
}
