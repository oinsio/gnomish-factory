package com.github.oinsio.gnomish.sandbox.environment

/**
 * A descendant that inherited a child's pipe and keeps writing to it after the child exited, on a
 * {@link FakePipe} (design D4, D6 of make-pipe-reads-non-parking, D2 as amended 2026-10-19): before
 * every availability query one byte ({@code '.'}) arrives, so a reader that reads only what is
 * available never sees an idle query — the one after the exit would end it — and is still open at
 * the post-exit bound. Deterministic, no thread: the
 * write rides the reader's own query, through {@link FakePipe#onQuery}, whose slot it takes for good.
 * A write after the pipe's {@link FakePipe#release} is dropped, as a holder's would be.
 */
final class WritingHolder {

    private WritingHolder() {
    }

    /** Starts the writing on {@code pipe}; call it before the reader's first query you want written to. */
    static void on(FakePipe pipe) {
        Runnable hook = null
        hook = {
            write(pipe)
            pipe.onQuery(hook)
        }
        pipe.onQuery(hook)
    }

    private static void write(FakePipe pipe) {
        try {
            pipe.write('.'.bytes)
        } catch (IllegalStateException released) {
            // The write end is closed: nothing reaches the reader any more.
        }
    }
}
