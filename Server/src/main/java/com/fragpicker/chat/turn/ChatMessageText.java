package com.fragpicker.chat.turn;

import com.fragpicker.common.api.ApiException;
import org.springframework.http.HttpStatus;

public final class ChatMessageText {
    private ChatMessageText() { }
    public static String normalize(String value) {
        if (value == null || value.length() > 4000) throw invalid();
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 2000 || normalized.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF
                || Character.isISOControl(cp) && cp != '\n' && cp != '\t')) throw invalid();
        return normalized;
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_MESSAGE", "消息最多2000个字符，请检查输入内容"); }
}
