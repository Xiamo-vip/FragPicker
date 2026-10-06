package com.fragpicker.chat.turn;

import java.util.concurrent.atomic.AtomicBoolean;

/** A fresh cancellation flag for one turn; HTTP delivery can cancel on disconnect. */
public final class TurnCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }
}
