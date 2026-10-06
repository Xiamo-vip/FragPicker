package com.fragpicker.knowledge.content;

import com.fragpicker.auth.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/fragments/{id}/transcript")
public class TranscriptController {
    private final TranscriptService transcript;
    public TranscriptController(TranscriptService transcript) { this.transcript = transcript; }
    @GetMapping
    public ResponseEntity<TranscriptResponse> get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
            @RequestParam(defaultValue = "SENTENCE") String kind, @RequestParam(defaultValue = "0") String ordinal,
            @RequestParam(defaultValue = "0") String offset, @RequestParam(defaultValue = "10") String limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(transcript.get(user.id(), id, kind, ordinal, offset, limit));
    }
}
