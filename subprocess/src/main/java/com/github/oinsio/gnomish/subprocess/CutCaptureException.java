package com.github.oinsio.gnomish.subprocess;

import java.time.Duration;

/**
 * The refusal of a cut capture as a value: the command exited on its own with code zero, but a
 * drain was still open when the post-exit bound expired, so its stdout or stderr may be a prefix of
 * what the command and its descendants wrote (design D15 of fix-terminal-receipt-convergence, as
 * amended 2026-10-17). Raised by one owner, {@link CaptureRunner}'s wait, so that no reader of the
 * invocation — a parser of its stdout, or a reader of the exit code alone — receives the prefix as
 * the whole output of a successful command.
 *
 * <p>The failure carries the {@link Captured} itself — the prefix read so far, the stderr, the
 * termination and the exit code keeping their meaning inside it — together with which stream was
 * cut and the bound, so the caller that owes the operator a line names the holder without a second
 * reading of the process. The message names the streams and the bound only, never captured text:
 * the capture is untrusted and the caller decides how it is shown.
 *
 * <p>Go's {@code os/exec} precedence: a non-zero exit, a timeout or an interruption is already a
 * failure, and keeps its partial output readable, with the cut named in its stderr instead of
 * raised beside it. Only an otherwise successful invocation is refused here.
 *
 * <p>The judgement itself is one function, {@link #refuses}, and so is the note's rendering,
 * {@link #withNotice}: the three value readers — this class's raiser {@link CaptureRunner}, {@code
 * GitExec} of {@code :gitobjects} (which throws its own library type, since its callers classify by
 * it) and {@code CapturedExec} of {@code :sandbox:core} (which fails the read the way a broken
 * stream does, since its callers classify by that) — ask it rather than spell the condition, so a
 * third copy of the rule cannot drift from the first (rule of three, {@code
 * .claude/rules/manual-sync-pairs.md}; task 8.11 of fix-terminal-receipt-convergence).
 *
 * <p>Implements FR17, FR19, NFR-O3 of fix-terminal-receipt-convergence.
 */
public final class CutCaptureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Which of a process's two streams the post-exit bound cut. */
    public enum CutStreams {
        /** Standard output only. */
        STDOUT("stdout"),
        /** Standard error only. */
        STDERR("stderr"),
        /** Both streams. */
        BOTH("stdout and stderr");

        private final String phrase;

        CutStreams(String phrase) {
            this.phrase = phrase;
        }

        /**
         * The streams as named in the cut notice — the words {@code GitExec}'s notice uses too.
         *
         * @return {@code stdout}, {@code stderr}, or {@code stdout and stderr}
         */
        public String phrase() {
            return phrase;
        }

        static CutStreams of(Drain stdout, Drain stderr) {
            if (stdout.cut()) {
                return stderr.cut() ? BOTH : STDOUT;
            }
            return STDERR;
        }
    }

    @SuppressWarnings("serial") // a record of strings, never serialized: the exception does not cross a JVM
    private final Captured captured;

    private final CutStreams streams;
    private final Duration bound;

    CutCaptureException(Captured captured, CutStreams streams, Duration bound) {
        super(notice(streams, bound));
        this.captured = captured;
        this.streams = streams;
        this.bound = bound;
    }

    /**
     * The one sentence a cut capture is named by — the refusal's message, and the note appended to
     * the stderr of a failed invocation whose drain was cut. Public so the other readers that
     * refuse a cut, {@code GitExec} and {@code CapturedExec}, name it in the same words rather than in
     * a copy of them.
     *
     * @param stdout the process's stdout drain, after {@link Drain#joinAll}
     * @param stderr the process's stderr drain, after {@link Drain#joinAll}
     * @param bound the post-exit bound the drains were joined with
     * @return the notice naming the cut streams and the bound
     */
    public static String notice(Drain stdout, Drain stderr, Duration bound) {
        return notice(CutStreams.of(stdout, stderr), bound);
    }

    /**
     * The notice for a reader that knows which stream was cut but holds no pair of drains — a
     * reader of one stream, such as {@code CapturedExec}.
     *
     * @param streams the streams still open at the bound
     * @param bound the post-exit bound the drains were joined with
     * @return the notice naming the cut streams and the bound
     */
    public static String notice(CutStreams streams, Duration bound) {
        return "output was cut: " + streams.phrase() + " still open at the post-exit bound of " + bound
                + " — a descendant held its pipes after the command exited";
    }

    /**
     * Whether a capture is refused rather than returned: the command exited on its own with code
     * zero while a drain was cut, so a reader would take a prefix for the whole value. Every other
     * termination is already a failure, whose partial output stays readable with the cut named
     * through {@link #withNotice} (Go's {@code os/exec} precedence: the exit error wins).
     *
     * @param termination how the invocation ended, as the reader classifies it after the joins
     * @param exitCode the process's exit value
     * @param cut whether {@link Drain#joinAll} reported a cut
     * @return whether the capture must be refused
     */
    public static boolean refuses(Termination termination, int exitCode, boolean cut) {
        return cut && termination == Termination.EXITED && exitCode == 0;
    }

    /**
     * A failed invocation's diagnostics with the cut named at their end, parenthesized — the one
     * shape every reader appends the note in.
     *
     * @param diagnostics the stderr (or the merged output) read so far
     * @param notice the notice naming the cut, from {@link #notice}
     * @return the diagnostics followed by the notice
     */
    public static String withNotice(String diagnostics, String notice) {
        return diagnostics + "(" + notice + ")";
    }

    /**
     * The capture as it stood when the bound let go: the termination ({@link Termination#EXITED}),
     * the exit code (zero), the stdout and stderr read so far, flagged {@link Captured#truncated()}.
     *
     * @return the cut capture
     */
    public Captured captured() {
        return captured;
    }

    /**
     * Which stream was still open at the bound.
     *
     * @return the cut streams
     */
    public CutStreams streams() {
        return streams;
    }

    /**
     * The post-exit bound the drains were joined with.
     *
     * @return the bound
     */
    public Duration bound() {
        return bound;
    }
}
