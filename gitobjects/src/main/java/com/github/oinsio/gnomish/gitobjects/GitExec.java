package com.github.oinsio.gnomish.gitobjects;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Runs one {@code git} plumbing command against a fixed {@code --git-dir}, capturing exit code,
 * stdout bytes, and stderr text, with optional stdin and a per-call stdout size cap. The only place
 * this library touches a subprocess — deliberately its own minimal runner (JDK only) rather than
 * the factory's {@code GitProcessRunner}, so {@code gitobjects} stays import-independent of the
 * factory (design D19). Ambient git config is neutralized and pathspec magic disabled, so behavior
 * and commit ids do not depend on the operator's environment.
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
record GitExec(Path gitDir, String gitBinary) {

    @SuppressWarnings("ArrayRecordComponent") // captured output bytes, consumed once by the caller
    record Result(int exitCode, byte[] stdout, String stderr, boolean truncated) {
        String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    Result run(List<String> args) {
        return run(args, null, Map.of(), -1);
    }

    Result run(List<String> args, byte @Nullable [] stdin, Map<String, String> extraEnv, long stdoutCap) {
        ProcessBuilder builder = new ProcessBuilder(commandLine(args));
        builder.directory(gitDir.toFile());
        Map<String, String> env = builder.environment();
        env.put("GIT_LITERAL_PATHSPECS", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_SYSTEM", "/dev/null");
        env.putAll(extraEnv);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GitObjectsException("could not launch git binary: " + gitBinary, e);
        }

        Thread stdinThread = feed(process, stdin);
        StringBuilder stderr = new StringBuilder();
        Thread stderrThread = drain(process.getErrorStream(), stderr);
        Capped out = readCapped(process.getInputStream(), stdoutCap);
        int exit = await(process, stdinThread, stderrThread);
        return new Result(exit, out.bytes, stderr.toString(), out.truncated);
    }

    private String[] commandLine(List<String> args) {
        List<String> line = new ArrayList<>(args.size() + 4);
        line.add(gitBinary);
        line.add("--git-dir=" + gitDir);
        // Disable hooks unconditionally: the only plumbing command here that would run one is
        // update-ref (the reference-transaction hook). Pointing hooksPath at a non-directory makes
        // git find no hook — the library's "no hook execution" guarantee holds regardless of what
        // the target clone has installed (design D19).
        line.add("-c");
        line.add("core.hooksPath=/dev/null");
        line.addAll(args);
        return line.toArray(new String[0]);
    }

    /**
     * Starts the stdin pump. Built through {@link Thread#ofPlatform()} rather than {@code new
     * Thread(...)} + {@code setDaemon} + {@code start} deliberately: the builder makes "daemon" and
     * "started" part of constructing the thread, so neither can be dropped independently. As three
     * separate statements, a mutation removing the {@code start()} call left every git command that
     * reads stdin blocked forever on a pipe nobody closes — and {@link #await} has no deadline, so
     * the hang surfaced as a PIT TIMED_OUT rather than a failing spec, in whichever covering spec
     * happened to run first (task 9.1 of split-into-modules). The builder calls all return values,
     * so no void-call mutation of this method exists to hang (FR25).
     */
    private static Thread feed(Process process, byte @Nullable [] stdin) {
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

    private static Thread drain(InputStream stream, StringBuilder sink) {
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
    private record Capped(byte[] bytes, boolean truncated) {}

    private static Capped readCapped(InputStream in, long cap) {
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

    // Package-private test seam: the interrupt path cannot be reached deterministically through
    // run() (git exits before waitFor blocks), so the spec pre-interrupts and calls await directly.
    static int await(Process process, Thread stdinThread, Thread stderrThread) {
        try {
            int exit = process.waitFor();
            stdinThread.join();
            stderrThread.join();
            return exit;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new GitObjectsException("interrupted waiting for git", e);
        }
    }
}
