package com.github.oinsio.gnomish.subprocess;

/**
 * How a supervised invocation ended — named separately from the exit code, because the three cases
 * mean different things to a caller deciding whether to re-attempt, report a failure, or stop.
 *
 * <p>The defect this type exists to remove is the sentinel exit code: an interrupted wait that
 * returns {@code -1} reads as an ordinary non-zero exit, so a caller spends its one bounded
 * re-attempt on a shutdown and then reports a remote outcome it never established.
 *
 * <p>Implements FR6 of bound-subprocess-commands.
 */
public enum Termination {

    /** The process ran to completion on its own; {@code exitCode} is the code it chose. */
    EXITED,

    /** The deadline expired and the process tree was terminated; whatever ran, ran unfinished. */
    TIMED_OUT,

    /** The waiting thread was interrupted; the process tree was terminated and the flag restored. */
    INTERRUPTED
}
