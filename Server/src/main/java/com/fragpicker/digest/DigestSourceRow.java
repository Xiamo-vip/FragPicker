package com.fragpicker.digest;

import java.time.LocalDate;

record DigestSourceRow(long fragmentId, long userId, LocalDate date, String title, String author,
                       String summary, String points, String categories, String keywords) {
    @Override public String toString() { return "DigestSourceRow[content=REDACTED]"; }
}
