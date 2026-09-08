package com.github.oinsio.gnomish.app.take;

/**
 * The abort fuse as one value: the {@link AbortHandler} that runs the infrastructure-abort
 * protocol when an engine run returns {@code Aborted}, and the threshold {@code K} of consecutive
 * aborts it trips at. The two are never used apart — the protocol is always run with the threshold
 * — so they travel together rather than as two adjacent parameters
 * (the seven-parameter rule of {@code .claude/rules/process-invariants.md}).
 *
 * <p>Implements FR9, FR12 of add-tracker-port (task 5.3's abort protocol); D2, D3.
 *
 * @param handler the protocol that parks or releases an aborted task; never null
 * @param threshold the fuse threshold {@code K}; positive
 */
public record AbortFuse(AbortHandler handler, int threshold) {

    public AbortFuse {
        if (threshold <= 0) {
            throw new IllegalArgumentException("abort-fuse threshold must be positive, got " + threshold);
        }
    }
}
