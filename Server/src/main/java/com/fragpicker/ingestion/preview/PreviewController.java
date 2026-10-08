package com.fragpicker.ingestion.preview;

import com.fragpicker.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/fragments/preview")
public class PreviewController {
    private final PreviewService previews;
    public PreviewController(PreviewService previews){this.previews=previews;}
    @PostMapping
    public ResponseEntity<PreviewService.PreviewResponse> preview(@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody PreviewRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(previews.preview(user.id(),request.shareText()));
    }
    public record PreviewRequest(@NotBlank @Size(max=4096) String shareText) {}
}
