package com.fragpicker.knowledge.search;

import com.fragpicker.auth.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/fragments/search")
public class SearchController {
    private final SearchService search;
    public SearchController(SearchService search) { this.search = search; }
    @PostMapping
    public ResponseEntity<SearchResponse> search(@AuthenticationPrincipal CurrentUser user, @RequestBody SearchRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.search(user.id(), request));
    }
}
