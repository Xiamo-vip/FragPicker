package com.fragpicker.ingestion.retry;

import com.fragpicker.auth.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/fragments")
public class RetryController {
    private final RetryService retries;
    public RetryController(RetryService retries) { this.retries=retries; }
    @PostMapping("/{id}/retry")
    public ResponseEntity<RetryResponse> retry(@AuthenticationPrincipal CurrentUser user,@PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody RetryRequest request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(retries.retry(user.id(),id,key,request));
    }
}
