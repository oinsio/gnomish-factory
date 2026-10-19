package com.github.oinsio.gnomish.subprocess

import java.time.Duration

/**
 * One reading of a {@link ChildOutput} on a virtual thread of its own, through either form — the
 * push form handing chunks to a {@link DrainSink}, or the pull form reading the stream view to its
 * {@code -1} — so {@code ChildOutputSpec} drives every feature through both (design D1, D2 of
 * make-pipe-reads-non-parking). What the reading saw is published by the thread's end: read it only
 * after {@link #join}.
 */
class ChildOutputRun {

    /** The two forms of the one loop. */
    enum Form {
        PUSH, VIEW

        /** The stop each form offers: {@code stop()} for the push form, the view's {@code close()}. */
        void stop(ChildOutput output) {
            if (this == PUSH) {
                output.stop()
            } else {
                output.stream().close()
            }
        }
    }

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream()
    private final Thread thread
    private boolean ended
    private IOException failure
    private boolean interruptedAfter

    private ChildOutputRun(Form form, ChildOutput output) {
        thread = Thread.ofVirtual().name('child-output-spec').start {
            try {
                if (form == Form.PUSH) {
                    push(output)
                } else {
                    pull(output)
                }
            } catch (IOException broken) {
                failure = broken
            }
            interruptedAfter = Thread.currentThread().isInterrupted()
        }
    }

    /** Starts reading {@code output} through {@code form}. */
    static ChildOutputRun start(Form form, ChildOutput output) {
        new ChildOutputRun(form, output)
    }

    /** Joins the reading thread for at most {@code bound}; whether it ended. */
    boolean join(Duration bound) {
        thread.join(bound)
        !thread.alive
    }

    /** What the reading kept, as UTF-8. */
    String captured() {
        bytes.toString('UTF-8')
    }

    /** Whether the reading ended at the stream's end: the sink's {@code end()}, or the view's {@code -1}. */
    boolean ended() {
        ended
    }

    /** The failure that ended the reading, or null. */
    IOException failure() {
        failure
    }

    /** Whether the reading thread's interrupt flag was set when the reading returned. */
    boolean interruptedAfter() {
        interruptedAfter
    }

    private void push(ChildOutput output) {
        output.pushTo(new DrainSink() {
                    @Override
                    void keep(byte[] chunk, int length) {
                        bytes.write(chunk, 0, length)
                    }

                    @Override
                    void end() {
                        ended = true
                    }
                })
    }

    private void pull(ChildOutput output) {
        byte[] buffer = new byte[64]
        int read
        while ((read = output.stream().read(buffer)) != -1) {
            bytes.write(buffer, 0, read)
        }
        ended = true
    }
}
