package com.fragpicker.ingestion;

import com.fragpicker.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/fragments")
public class SubmissionController {
    private final SubmissionService submissions;
    public SubmissionController(SubmissionService submissions) { this.submissions = submissions; }

    @PostMapping
    public ResponseEntity<SubmissionResponse> submit(@AuthenticationPrincipal CurrentUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody SubmissionRequest request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(submissions.submit(user.id(), key, request));
    }
}
