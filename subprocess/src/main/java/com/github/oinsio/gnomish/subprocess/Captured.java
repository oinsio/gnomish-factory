package com.github.oinsio.gnomish.subprocess;

/**
 * The outcome of one capture-shaped invocation: {@link Supervision}'s named termination and exit
 * value, plus stdout and stderr captured as separate UTF-8 strings, and whether a drain was cut.
 *
 * <p>The streams are what the drains had read when the post-exit bound let go of them. On a kill
 * that is the output captured so far, not a complete transcript — the point of returning it is
 * that a timed-out command's partial diagnostics are the most useful thing a caller can report
 * (FR3). After an exit whose pipe a descendant still held, it is a prefix too, and {@code
 * truncated} says so: distinct from the termination and the exit code, which keep their meaning.
 * A cut capture of a clean exit with code zero never reaches a caller as a return value — it is
 * carried by {@link CutCaptureException} instead (design D15 as amended 2026-10-17); a returned
 * capture with {@code truncated} set is a failure's diagnostics, its stderr ending with the note
 * that names the cut.
 *
 * <p>Implements FR2, FR3, FR6 of bound-subprocess-commands; FR17, FR21, NFR-O3 of
 * fix-terminal-receipt-convergence.
 *
 * @param termination how the invocation ended
 * @param exitCode the process's exit value; meaningful on {@link Termination#EXITED}
 * @param stdout standard output captured as UTF-8, in full unless {@code truncated}
 * @param stderr standard error captured as UTF-8, in full unless {@code truncated}; on a returned
 *     cut capture, followed by the parenthesized note naming the cut streams and the bound
 * @param truncated whether a drain was still open when the post-exit bound expired, so either
 *     stream may be a prefix of what the command and its descendants wrote
 * @param sweep what the process group sweep found once the drains were joined (design D19): the
 *     caller's DEBUG line when it found a survivor (NFR-O3), nothing else
 */
public record Captured(
        Termination termination, int exitCode, String stdout, String stderr, boolean truncated, GroupSweep sweep) {}
