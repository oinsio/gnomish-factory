package com.github.oinsio.gnomish.serveobservability;

/**
 * One row of the snapshot's kept-environment inventory (NFR-O1): a task whose environment is
 * stopped-but-preserved, how old it is, and how much margin is left before the aged reaper
 * disposes it. Seconds rather than {@code Duration}, matching {@link ReaperVital}'s {@code
 * intervalSeconds} precedent — the snapshot's wire form carries no ISO-8601 durations, so the
 * domain record already speaks the unit the reader computes in.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O1 of add-serve-sandbox-lifecycle.
 *
 * @param taskKey the sanitized environment key the kept objects belong to; never blank
 * @param ageSeconds how old the kept environment is; never negative
 * @param untilReapSeconds how long until the aged reaper disposes it; never negative
 */
public record KeptEnvironmentEntry(String taskKey, long ageSeconds, long untilReapSeconds) {

    public KeptEnvironmentEntry {
        requireNonBlankTaskKey(taskKey);
        ageSeconds = requireNonNegative(ageSeconds, "ageSeconds");
        untilReapSeconds = requireNonNegative(untilReapSeconds, "untilReapSeconds");
    }

    /**
     * Kept as a shared static method rather than inline in the compact constructor: PIT's record
     * filter suppresses all mutations inside a record's canonical constructor, which would silently
     * exempt this validation from the 100% mutation gate.
     */
    private static void requireNonBlankTaskKey(String taskKey) {
        if (taskKey.isBlank()) {
            throw new IllegalArgumentException("KeptEnvironmentEntry.taskKey must not be blank");
        }
    }

    private static long requireNonNegative(long value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("KeptEnvironmentEntry." + component + " must not be negative");
        }
        return value;
    }
}
