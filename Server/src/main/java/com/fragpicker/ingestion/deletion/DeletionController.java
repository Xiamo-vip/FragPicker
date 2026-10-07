package com.fragpicker.ingestion.deletion;

import com.fragpicker.auth.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/fragments")
public class DeletionController {
    private final DeletionService deletions;
    public DeletionController(DeletionService deletions) { this.deletions=deletions; }
    @DeleteMapping("/{id}")
    public ResponseEntity<DeletionResponse> delete(@AuthenticationPrincipal CurrentUser user,@PathVariable long id) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(deletions.delete(user.id(),id));
    }
}
