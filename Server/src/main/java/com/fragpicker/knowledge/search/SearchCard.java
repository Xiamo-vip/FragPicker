package com.fragpicker.knowledge.search;

import java.time.LocalDate;

public record SearchCard(long fragmentId, String title, String author, LocalDate businessDate, String summary, String categories, boolean video, boolean cover) {
    @Override public String toString() { return "SearchCard[content=REDACTED]"; }
}
