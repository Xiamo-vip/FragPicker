package com.fragpicker.digest;

import java.time.LocalDate;
import java.util.UUID;

public record DigestLease(long id, long userId, LocalDate date, long revision, String token) {
    public DigestLease {
        if (id < 1 || userId < 1 || date == null || revision < 1 || token == null || !UUID.fromString(token).toString().equals(token))
            throw new IllegalArgumentException("Invalid digest lease");
    }
    @Override public String toString() { return "DigestLease[id=" + id + ", revision=" + revision + ", token=REDACTED]"; }
}
