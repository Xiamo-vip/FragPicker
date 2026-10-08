package com.fragpicker.ingestion.preview;

import com.fragpicker.integration.parsevideo.*;

/** Dedicated bounded parser; regular ingestion keeps its configurable long-running timeout. */
public class PreviewParser {
    private final ParseVideoClient client;
    public PreviewParser(ParseVideoClient client){this.client=client;}
    public ParsedVideo parse(String link){return client.parse(link);}
}
