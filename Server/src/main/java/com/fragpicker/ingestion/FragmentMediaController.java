package com.fragpicker.ingestion;

import com.fragpicker.auth.CurrentUser;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.integration.oss.MediaKind;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/fragments")
public class FragmentMediaController {
    private final FragmentMediaService media;
    public FragmentMediaController(FragmentMediaService media) { this.media = media; }
    @GetMapping("/{id}/media")
    public ResponseEntity<FragmentMediaResponse> get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
            @RequestParam(defaultValue = "VIDEO") String kind) {
        MediaKind selected;
        try { selected = MediaKind.valueOf(kind); }
        catch (IllegalArgumentException invalid) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA_KIND", "媒体类型必须为 VIDEO 或 COVER"); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(media.get(user.id(), id, selected));
    }
}
