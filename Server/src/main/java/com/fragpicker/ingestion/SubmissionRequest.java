package com.fragpicker.ingestion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmissionRequest(@NotBlank @Size(max = 4096) String shareText, @Size(max = 1000) String note) {
    @Override public String toString() { return "SubmissionRequest[content=REDACTED]"; }
}
