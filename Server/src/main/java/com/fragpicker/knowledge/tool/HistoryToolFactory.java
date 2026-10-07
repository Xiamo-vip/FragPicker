package com.fragpicker.knowledge.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.auth.CurrentUser;
import com.fragpicker.knowledge.search.SearchService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("database")
public class HistoryToolFactory {
    private final SearchService search;
    private final ObjectMapper json;
    private final com.fragpicker.digest.DigestReadService digests;
    public HistoryToolFactory(SearchService search, ObjectMapper json, com.fragpicker.digest.DigestReadService digests) { this.search = search; this.json = json; this.digests=digests; }
    /** Bind once per authenticated conversation turn. Never share an instance between users/turns. */
    public HistorySearchTool bind(CurrentUser user) {
        if (user == null || user.id() == null || user.id() < 1) throw new IllegalArgumentException("Missing authenticated tool owner");
        return new HistorySearchTool(user.id(), search, json, digests);
    }
}
