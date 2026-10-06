package com.fragpicker.history;

import com.fragpicker.common.api.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
@Profile("database")
public class CalendarService {
    private final CalendarMapper calendar;
    public CalendarService(CalendarMapper calendar) { this.calendar = calendar; }
    @Transactional(readOnly = true)
    public CalendarResponse month(long owner, String rawMonth) {
        YearMonth month;
        try { if (rawMonth == null || !rawMonth.matches("[0-9]{4}-[0-9]{2}")) throw invalid(); month = YearMonth.parse(rawMonth); if (month.getYear() < 1000) throw invalid(); }
        catch (DateTimeException bad) { throw invalid(); }
        var indexed = new HashMap<LocalDate, CalendarMapper.DayCount>();
        for (var day : calendar.month(owner, month.atDay(1), month.atEndOfMonth())) indexed.put(day.date(), day);
        var days = new ArrayList<CalendarResponse.Day>(month.lengthOfMonth()); long total = 0;
        for (int number = 1; number <= month.lengthOfMonth(); number++) {
            var date = month.atDay(number); var count = indexed.getOrDefault(date, new CalendarMapper.DayCount(date, 0, 0, 0));
            days.add(new CalendarResponse.Day(date, count.total(), count.ready(), count.total() - count.ready() - count.failed(), count.failed())); total += count.total();
        }
        return new CalendarResponse(month.toString(), total, days);
    }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CALENDAR", "请提供1000至9999年范围内的 YYYY-MM 月份"); }
}
