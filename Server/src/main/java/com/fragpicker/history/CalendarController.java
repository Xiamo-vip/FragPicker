package com.fragpicker.history;

import com.fragpicker.auth.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/calendar")
public class CalendarController {
    private final CalendarService calendar;
    public CalendarController(CalendarService calendar) { this.calendar = calendar; }
    @GetMapping
    public ResponseEntity<CalendarResponse> month(@AuthenticationPrincipal CurrentUser user, @RequestParam(required = false) String month) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(calendar.month(user.id(), month));
    }
}
