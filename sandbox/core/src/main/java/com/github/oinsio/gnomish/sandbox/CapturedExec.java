package com.github.oinsio.gnomish.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * One exec'd command captured whole: the exit code and everything it wrote —
 * the shared shape for every caller that runs a short command through {@link
 * TaskExecutionEnvironment#exec(ExecCommand)} and wants its full output rather
 * than a stream (an in-box service commit, a salvage command, a self-check
 * probe).
 *
 * <p>The output is drained on a virtual thread <em>concurrently</em> with the
 * supervised wait, and joined only after the wait has resolved (design D2 of
 * bound-subprocess-commands). Reading the pipe to EOF on the calling thread
 * first — the shape each call site hand-rolled before this class — is the
 * reproduced hang: a hung in-box command holds its stdout open, the read never
 * returns, the wait is never reached, and a pipe read blocked in the OS cannot
 * be interrupted.
 *
 * <p>A wait cut short by an interrupt is reported by name — an {@link
 * InterruptedIOException} inside the thrown {@link UncheckedIOException} — with
 * the interrupt flag left set, never as the killed process's exit code, which a
 * caller could not tell from a code the command genuinely chose (FR11). The
 * classification reads the flag the supervised wait restores; an external
 * interrupt landing in the instant after a clean exit is conservatively
 * classified the same way, which at worst re-runs work that did complete.
 *
 * <p>Implements FR2, FR11 of bound-subprocess-commands.
 *
 * @param exitCode the exit code the wait reported; meaningful only because an
 *     interrupted wait never reaches a {@code CapturedExec} at all
 * @param output everything the command wrote to the captured stream, as UTF-8
 */
public record CapturedExec(int exitCode, String output) {

    /**
     * Runs the capture over an already-started handle: starts the drain, waits
     * through the handle's supervised wait, and joins the drain once the wait
     * has resolved.
     *
     * @param handle the started command's handle; its output stream is consumed and closed
     * @param what the operation for failure messages, e.g. {@code "in-box state commit"}
     * @return the exit code and the complete captured output; never null
     * @throws UncheckedIOException if the wait was interrupted (cause {@link
     *     InterruptedIOException}, flag left set) or the output stream broke mid-read
     */
    public static CapturedExec of(ExecHandle handle, String what) {
        CompletableFuture<String> drain = CompletableFuture.supplyAsync(
                () -> read(handle.output(), what),
                task -> Thread.ofVirtual().name("exec-capture").start(task));
        int exitCode = handle.waitForExit();
        if (Thread.currentThread().isInterrupted()) {
            // The supervised wait killed the tree and restored the flag; the drain thread is
            // abandoned — a virtual thread parked on a read, ending when the killed pipe closes.
            throw new UncheckedIOException(new InterruptedIOException(
                    what + " did not complete: the wait was interrupted and the process tree was killed"));
        }
        return new CapturedExec(exitCode, join(drain));
    }

    private static String read(InputStream in, String what) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + what + " output", e);
        }
    }

    /**
     * Joins the drain, unbounded and uninterruptible: it is reached only after a
     * real exit, where waiting is what makes the capture complete, and the
     * interrupt case has already thrown above ({@link CompletableFuture#join()}
     * is what keeps this method free of a second interrupt-handling site).
     */
    private static String join(CompletableFuture<String> drain) {
        try {
            return drain.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof UncheckedIOException broken) {
                throw broken;
            }
            throw e;
        }
    }
}
