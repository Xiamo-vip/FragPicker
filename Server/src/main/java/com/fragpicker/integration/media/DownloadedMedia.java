package com.fragpicker.integration.media;

import java.io.IOException;
import java.nio.file.*;

public record DownloadedMedia(Path file, long sizeBytes, String contentType) implements AutoCloseable {
    @Override public void close() throws IOException { Files.deleteIfExists(file); }
    @Override public String toString() { return "DownloadedMedia[sizeBytes=" + sizeBytes + ", contentType=" + contentType + "]"; }
}
