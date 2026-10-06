package com.fragpicker.knowledge.search;

import java.time.LocalDate;

/** Created by the service from the authenticated owner; never accepted as a request body. */
public record SearchScope(long userId, String query, LocalDate fromDate, LocalDate toDate, String category, String author, String keyword, int limit) {
    @Override public String toString() { return "SearchScope[query=REDACTED, filters=REDACTED]"; }
}
