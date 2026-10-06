package com.fragpicker.chat;

public record CreateSessionRequest(String title) {
    @Override public String toString() { return "CreateSessionRequest[title=REDACTED]"; }
}
