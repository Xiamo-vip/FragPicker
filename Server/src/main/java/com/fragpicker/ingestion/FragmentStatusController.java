package com.fragpicker.ingestion;

import com.fragpicker.auth.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/fragments")
public class FragmentStatusController {
    private final FragmentStatusService fragments;
    public FragmentStatusController(FragmentStatusService fragments) { this.fragments = fragments; }

    @GetMapping("/{id}")
    public ResponseEntity<FragmentStatusResponse> status(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(fragments.get(user.id(), id));
    }
}
