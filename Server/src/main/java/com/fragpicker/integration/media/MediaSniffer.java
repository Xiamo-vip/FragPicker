package com.fragpicker.integration.media;

import com.fragpicker.integration.oss.MediaKind;
import java.nio.charset.StandardCharsets;
import static com.fragpicker.integration.media.MediaDownloadFailure.Code.UNSUPPORTED_CONTENT;

final class MediaSniffer {
    static String detect(byte[] bytes, MediaKind kind) {
        if (kind == MediaKind.COVER) {
            if (starts(bytes, 137, 80, 78, 71, 13, 10, 26, 10)) return "image/png";
            if (starts(bytes, 255, 216, 255)) return "image/jpeg";
            if (text(bytes, 0, 6).equals("GIF87a") || text(bytes, 0, 6).equals("GIF89a")) return "image/gif";
            if (text(bytes, 0, 4).equals("RIFF") && text(bytes, 8, 4).equals("WEBP")) return "image/webp";
        } else if (kind == MediaKind.VIDEO) {
            if (bytes.length >= 12 && text(bytes, 4, 4).equals("ftyp")) {
                return text(bytes, 8, 4).equals("qt  ") ? "video/quicktime" : "video/mp4";
            }
            if (starts(bytes, 0x1a, 0x45, 0xdf, 0xa3)) return "video/x-matroska";
            if (starts(bytes, 70, 76, 86)) return "video/x-flv";
        }
        throw new MediaDownloadFailure(UNSUPPORTED_CONTENT, false);
    }

    private static String text(byte[] bytes, int offset, int length) {
        return bytes.length >= offset + length ? new String(bytes, offset, length, StandardCharsets.US_ASCII) : "";
    }
    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 255) != prefix[i]) return false;
        return true;
    }
}
