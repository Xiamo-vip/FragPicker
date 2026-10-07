package com.fragpicker.digest;

import com.fragpicker.auth.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/daily-digests")
public class DigestManualController {
    private final DigestManualService digests;
    public DigestManualController(DigestManualService digests) { this.digests=digests; }
    @PostMapping("/{date}/regenerate")
    public ResponseEntity<DigestRegenerateResponse> regenerate(@AuthenticationPrincipal CurrentUser user,@PathVariable String date,
            @RequestHeader(name="Idempotency-Key",required=false) String key) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(digests.regenerate(user.id(),date,key));
    }
}
