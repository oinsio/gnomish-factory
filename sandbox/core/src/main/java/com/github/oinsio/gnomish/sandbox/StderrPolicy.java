package com.github.oinsio.gnomish.sandbox;

import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Where a process started through {@link TaskExecutionEnvironment#exec(ExecCommand)} sends its
 * standard error (design D18 of fix-terminal-receipt-convergence) — the policy that replaced the
 * {@code mergeStderr} flag, whose {@code false} arm left the stream as a pipe nothing read.
 *
 * <ul>
 *   <li>{@link Merged} — folded into the one output stream: a command check and an in-box git
 *       command read one chronological stream.
 *   <li>{@link Piped} — a separate pipe the caller drains itself: the container file channel
 *       reads both pipes with a drain each (D17).
 *   <li>{@link ToFile} — a host file, written by the process directly: the agent round's CLI,
 *       whose stdout is the live stream-json pipe and whose stderr is read after the exit from the
 *       round scratch directory. In a box the file is the host-side {@code docker exec} client's
 *       stderr, which carries the in-box process's.
 * </ul>
 *
 * <p>Each arm applies itself to the {@link ProcessBuilder} the adapter built, so the host and the
 * container adapter — the two callers that start the process — cannot apply it differently.
 *
 * <p>Implements FR20 of fix-terminal-receipt-convergence.
 */
public sealed interface StderrPolicy {

    /** Stderr merged into the output stream. */
    StderrPolicy MERGED = new Merged();

    /** Stderr as a separate pipe the caller drains itself. */
    StderrPolicy PIPED = new Piped();

    /**
     * Stderr written to {@code file}, which the launch creates or truncates.
     *
     * @param file the host file; never null
     * @return the policy; never null
     */
    static StderrPolicy toFile(Path file) {
        return new ToFile(file);
    }

    /**
     * Sets the builder's stderr disposition to this policy, replacing whatever it held.
     *
     * @param builder the builder of the process about to start; never null
     */
    void applyTo(ProcessBuilder builder);

    /** Stderr folded into stdout, one chronological stream. */
    record Merged() implements StderrPolicy {
        @Override
        public void applyTo(ProcessBuilder builder) {
            builder.redirectErrorStream(true);
        }
    }

    /** Stderr as its own pipe; the caller that chose it drains it. */
    record Piped() implements StderrPolicy {
        @Override
        public void applyTo(ProcessBuilder builder) {
            builder.redirectErrorStream(false);
            builder.redirectError(Redirect.PIPE);
        }
    }

    /**
     * Stderr to a host file.
     *
     * @param file the host file the process writes its stderr to; never null
     */
    record ToFile(Path file) implements StderrPolicy {

        public ToFile {
            Objects.requireNonNull(file, "file");
        }

        @Override
        public void applyTo(ProcessBuilder builder) {
            builder.redirectErrorStream(false);
            builder.redirectError(file.toFile());
        }
    }
}
