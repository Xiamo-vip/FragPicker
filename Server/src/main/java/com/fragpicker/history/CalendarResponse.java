package com.fragpicker.history;

import java.time.LocalDate;
import java.util.List;

public record CalendarResponse(String month, long total, List<Day> days) {
    public CalendarResponse { days = List.copyOf(days); }
    public record Day(LocalDate date, long total, long ready, long processing, long failed, boolean hasSummary, boolean summaryOutdated) { }
}
