package com.fragpicker.history;

import com.fragpicker.auth.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/fragments")
public class DailyFragmentsController {
    private final DailyFragmentsService history;
    public DailyFragmentsController(DailyFragmentsService history) { this.history = history; }
    @GetMapping
    public ResponseEntity<DailyFragmentsResponse> list(@AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) String date, @RequestParam(required = false) String before, @RequestParam(required = false) String limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.list(user.id(), date, before, limit));
    }
}
