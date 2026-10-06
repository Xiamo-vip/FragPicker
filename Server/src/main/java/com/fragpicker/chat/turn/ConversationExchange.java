package com.fragpicker.chat.turn;

/** Completed exchanges loaded by the server from an owned conversation, not client-authored roles. */
public record ConversationExchange(String question, String answer) {
    @Override public String toString() { return "ConversationExchange[content=REDACTED]"; }
}
