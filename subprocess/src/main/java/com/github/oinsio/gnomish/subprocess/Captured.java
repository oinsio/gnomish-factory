package com.github.oinsio.gnomish.subprocess;

/**
 * The outcome of one capture-shaped invocation: {@link Supervision}'s named termination and exit
 * value, plus stdout and stderr captured as separate UTF-8 strings.
 *
 * <p>The streams are what the drains had read when the wait resolved. On a kill that is the output
 * captured so far, not a complete transcript — the point of returning it is that a timed-out
 * command's partial diagnostics are the most useful thing a caller can report (FR3).
 *
 * <p>Implements FR2, FR3, FR6 of bound-subprocess-commands.
 *
 * @param termination how the invocation ended
 * @param exitCode the process's exit value; meaningful on {@link Termination#EXITED}
 * @param stdout standard output captured as UTF-8, in full on a normal exit
 * @param stderr standard error captured as UTF-8, in full on a normal exit
 */
public record Captured(Termination termination, int exitCode, String stdout, String stderr) {}
