package com.github.oinsio.gnomish.logtext;

/**
 * The streak identity of one fault, for the sites that report to a {@link RepeatSuppressor}.
 *
 * <p>A suppressor tells repeats apart by their reason string, so the reason must change exactly
 * when the fault changes: a connection reset arriving in the middle of a run of 5xx answers is
 * news, and must restart the streak rather than be counted as more of the same. The type alone is
 * too coarse (every tracker fault is a {@code RuntimeException}) and the message alone is too
 * fine-grained where it is absent, so the identity is both.
 *
 * <p>Read at the reporting site rather than interpolated into the log call: the throwable itself
 * still rides the line as the trailing argument, which is what keeps the stack and the cause chain
 * (`.claude/rules/logging.md`).
 *
 * <p>One implementation rather than the same three lines repeated per poll loop
 * (`.claude/rules/manual-sync-pairs.md`, preference 1: shared abstraction over declared pair).
 *
 * <p>Implements FR4 of harden-logging-observability.
 */
public final class FailureReason {

    private FailureReason() {}

    /**
     * The reason string for {@code failure}: its type, plus its own words when it has any.
     *
     * @param failure the fault a poll or retry loop just caught; never null
     * @return a stable identity for this fault, distinct from a different fault; never null
     */
    public static String of(Throwable failure) {
        String message = failure.getMessage();
        return message == null
                ? failure.getClass().getName()
                : failure.getClass().getName() + ": " + message;
    }
}
