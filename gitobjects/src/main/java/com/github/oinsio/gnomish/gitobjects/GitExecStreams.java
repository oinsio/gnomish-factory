package com.github.oinsio.gnomish.gitobjects;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * The stream I/O of one {@link GitExec} run: the stdin feed and stderr drain pump threads, and the
 * capped on-thread stdout read. Split out of {@code GitExec} purely along the file-size rule
 * (process-invariants) — the policy is unchanged and still {@code GitExec}'s own: the cap is
 * enforced while the bytes arrive, stdin/stderr are pumped concurrently, and an interrupt mid-read
 * raises the loud {@link GitObjectsException}.
 *
 * <p>Implements FR25 of add-sandbox-core; FR13 of bound-subprocess-commands.
 */
final class GitExecStreams {

    private GitExecStreams() {}

    /**
     * Starts the stdin pump. Built through {@link Thread#ofPlatform()} rather than {@code new
     * Thread(...)} + {@code setDaemon} + {@code start} deliberately: the builder makes "daemon" and
     * "started" part of constructing the thread, so neither can be dropped independently. As three
     * separate statements, a mutation removing the {@code start()} call left every git command that
     * reads stdin blocked forever on a pipe nobody closes — and {@link GitExec#await} has no
     * deadline, so the hang surfaced as a PIT TIMED_OUT rather than a failing spec, in whichever
     * covering spec happened to run first (task 9.1 of split-into-modules). The builder calls all
     * return values, so no void-call mutation of this method exists to hang (FR25).
     */
    static Thread feed(Process process, byte @Nullable [] stdin) {
        return Thread.ofPlatform().name("gitexec-stdin").daemon(true).start(() -> {
            try (OutputStream os = process.getOutputStream()) {
                if (stdin != null) {
                    os.write(stdin);
                }
            } catch (IOException ignored) {
                // The process may have exited before consuming stdin; its exit code speaks.
            }
        });
    }

    static Thread drain(InputStream stream, StringBuilder sink) {
        Thread thread = new Thread(
                () -> {
                    try {
                        sink.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                        // Partial stderr is acceptable diagnostic context.
                    }
                },
                "gitexec-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @SuppressWarnings("ArrayRecordComponent") // transient capture buffer, never retained or shared
    record Capped(byte[] bytes, boolean truncated) {}

    static Capped readCapped(InputStream in, long cap) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            if (cap < 0) {
                in.transferTo(buffer);
                return new Capped(buffer.toByteArray(), false);
            }
            byte[] chunk = new byte[8192];
            long remaining = cap;
            int read;
            while (hasRemainingCapacity(remaining)
                    && (read = in.read(chunk, 0, (int) Math.min(chunk.length, remaining))) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new GitObjectsException("interrupted while reading git output", new InterruptedException());
                }
                buffer.write(chunk, 0, read);
                remaining -= read;
            }
            boolean truncated = in.read() != -1;
            in.transferTo(OutputStream.nullOutputStream());
            return new Capped(buffer.toByteArray(), truncated);
        } catch (IOException e) {
            throw new GitObjectsException("failed reading git output", e);
        }
    }

    /**
     * PIT M4 documented exception (build.gradle has the full rationale): {@code @DoNotMutate}
     * because flipping this boundary to {@code remaining >= 0} makes {@link #readCapped} busy-spin
     * forever the moment {@code remaining} hits exactly zero — a real read of length zero always
     * returns {@code 0} immediately (never blocks, per {@link InputStream#read(byte[], int, int)}),
     * so the loop condition stays true and nothing ever changes. Observed in practice: whenever a
     * cap and the 8KiB read-chunk size divide evenly, or a cap exactly matches a blob's length, the
     * mutated boundary hung a PIT minion for 30+ minutes across three unrelated covering tests
     * (a stdout-cap-of-zero case, a readBlob-at-exact-cap case, and an oversized-pin case whose
     * 1 MiB cap happens to be a multiple of 8192) — no single test-side fix (bounded waits,
     * interruption checks) closes every such coincidence, since any future caller with a
     * chunk-aligned cap reopens it. Isolated into its own method so only this one boundary is
     * exempted; every other mutation in {@link #readCapped} stays covered.
     */
    @DoNotMutate
    private static boolean hasRemainingCapacity(long remaining) {
        return remaining > 0;
    }
}
