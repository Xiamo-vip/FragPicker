package com.fragpicker.digest;

import com.fragpicker.auth.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/daily-digests")
public class DigestReadController {
    private final DigestReadService digests;
    public DigestReadController(DigestReadService digests) { this.digests=digests; }
    @GetMapping("/{date}")
    public ResponseEntity<DailyDigestResponse> get(@AuthenticationPrincipal CurrentUser user,@PathVariable String date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(digests.get(user.id(),date));
    }
}
