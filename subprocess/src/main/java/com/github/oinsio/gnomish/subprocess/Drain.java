package com.github.oinsio.gnomish.subprocess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * One output stream read on a virtual thread of its own, concurrently with the running process
 * (FR2, design D2).
 *
 * <p>Reading a stream to the end before waiting for the process — the shape every caller in this
 * repository had — is the reproduced hang: a child that holds its stdout open never lets the read
 * return, and the wait is never reached. Reading only after the wait is no better, since an OS pipe
 * buffer that fills blocks the child itself. So the drains start immediately after the process does
 * and are joined once the wait has resolved.
 *
 * <p>The join is bounded on the kill path, which is why {@link #join} takes its bound per call: a
 * process that escaped the kill snapshot can inherit the pipe and hold it open, and "the drains
 * finished" must never be a precondition for returning a result. A drain left behind that way is a
 * virtual thread parked on a read, holding no OS thread, and it ends when the pipe finally closes.
 *
 * <p>{@link ByteArrayOutputStream}'s methods are synchronized, so reading the buffer out from under
 * a drain that is still writing yields a consistent prefix rather than a torn one.
 *
 * <p>Implements FR2, NFR-P1 of bound-subprocess-commands.
 */
final class Drain {

    private final Thread thread;
    private final ByteArrayOutputStream buffer;

    private Drain(Thread thread, ByteArrayOutputStream buffer) {
        this.thread = thread;
        this.buffer = buffer;
    }

    /** Starts draining {@code stream} on a named virtual thread. */
    static Drain start(InputStream stream, String name) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread thread = Thread.ofVirtual().name(name).start(() -> {
            try {
                stream.transferTo(buffer);
            } catch (IOException broken) {
                // A stream that fails mid-command keeps whatever was captured: partial output is
                // acceptable diagnostic context, and the termination says what happened.
            }
        });
        return new Drain(thread, buffer);
    }

    /**
     * Joins the drain and returns what it captured, as UTF-8.
     *
     * @param bound how long to wait for the drain, or {@code null} to wait as long as it takes —
     *     which is the normal-exit path, where waiting is what makes the capture complete
     * @return the captured output; a prefix of it if the bound expired
     */
    String join(@Nullable Duration bound) {
        try {
            if (bound == null) {
                thread.join();
            } else {
                thread.join(bound);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
