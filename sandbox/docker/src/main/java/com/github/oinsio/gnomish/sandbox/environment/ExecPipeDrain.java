package com.github.oinsio.gnomish.sandbox.environment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One {@code docker exec} pipe read on a virtual thread of its own, concurrently
 * with the running exec — the streaming-caller reader design D10 of
 * bound-subprocess-commands leaves with the caller, for {@link
 * ContainerFileChannel}.
 *
 * <p>Reading a pipe to the end on the calling thread <em>before</em> the
 * supervised wait is the hang design D2 names: a hung in-box command holds its
 * stdout open, so the read never returns and the wait is never reached — and a
 * stream over an OS pipe is not interruptible, so the channel's named interrupt
 * outcome could never be reached either. Draining after the wait is no better,
 * since a pipe buffer that fills blocks the in-box process itself, which is why
 * stderr is drained here too even though the channel discards it.
 *
 * <p>The subprocess module's own drain is not reusable here: it decodes to UTF-8,
 * and this channel carries arbitrary file bytes that such a round trip would
 * corrupt.
 *
 * <p>{@link ByteArrayOutputStream}'s methods are synchronized, so reading the
 * buffer out from under a drain still writing yields a consistent prefix rather
 * than a torn one.
 *
 * <p>Implements FR2, FR11 of bound-subprocess-commands.
 */
final class ExecPipeDrain {

    private static final Logger log = LoggerFactory.getLogger(ExecPipeDrain.class);

    private final Thread thread;
    private final ByteArrayOutputStream buffer;

    private ExecPipeDrain(Thread thread, ByteArrayOutputStream buffer) {
        this.thread = thread;
        this.buffer = buffer;
    }

    /** Starts draining {@code stream} on a named virtual thread. */
    static ExecPipeDrain start(InputStream stream, String name) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread thread = Thread.ofVirtual().name(name).start(() -> {
            try (stream) {
                stream.transferTo(buffer);
            } catch (IOException broken) {
                // A pipe that fails mid-exec keeps whatever was captured: the supervised
                // termination, not this stream, says what happened to the invocation.
                log.debug("in-box exec pipe {} broke mid-read: {}", name, broken.toString());
            }
        });
        return new ExecPipeDrain(thread, buffer);
    }

    /**
     * Joins the drain and returns what it captured. The join is unbounded because
     * the only caller reaches it on the exited path, where waiting is what makes
     * the capture complete; the kill paths abandon the drain instead — a virtual
     * thread parked on a read, holding no OS thread, ending when the pipe closes.
     */
    byte[] join() {
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return buffer.toByteArray();
    }
}
