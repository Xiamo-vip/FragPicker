package com.fragpicker.digest;

final class DigestText {
    private DigestText() { }
    static void require(String text, int limit) {
        if (text == null || text.isBlank() || text.length() > limit * 2 || text.codePointCount(0, text.length()) > limit
                || text.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF || Character.isISOControl(cp) && cp != '\n' && cp != '\t')) throw new IllegalArgumentException("Invalid digest text");
    }
    static String preview(String value, int limit) {
        if (value == null) return "";
        return value.codePointCount(0, value.length()) <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit));
    }
}
