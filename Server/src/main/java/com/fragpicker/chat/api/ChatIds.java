package com.fragpicker.chat.api;
import com.fragpicker.common.api.ApiException;
import org.springframework.http.HttpStatus;
final class ChatIds {
    private ChatIds() { }
    static long parse(String value) {
        try { if (value == null || !value.matches("[0-9]{1,19}")) throw new NumberFormatException(); long id = Long.parseLong(value); if (id < 1) throw new NumberFormatException(); return id; }
        catch (NumberFormatException invalid) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_ID", "请使用有效的对话和消息编号"); }
    }
}
