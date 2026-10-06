package com.fragpicker.knowledge.content;

import com.fragpicker.auth.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/fragments/{id}/content")
public class ContentController {
    private final ContentService content;
    public ContentController(ContentService content) { this.content = content; }
    @GetMapping
    public ResponseEntity<ContentResponse> get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(content.get(user.id(), id));
    }
}
