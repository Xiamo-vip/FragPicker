package com.fragpicker.chat.turn;

/** Runs on the calling turn thread, never on the model's I/O callback. Text is untrusted model output. */
public interface ChatTurnListener {
    default void roundStarted(int round) { }
    default void delta(int round, String text) { }
    /** If intermediate=true, this round is tool narration, not the persisted final answer. */
    default void roundEnded(int round, boolean intermediate) { }
    default void heartbeat() { }
}
