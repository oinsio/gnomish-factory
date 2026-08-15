package com.github.oinsio.gnomish.adapter.tracker.github;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry of {@link FixtureIssue} state for one WireMock server instance
 * (task 4.16): keyed by issue number, plus the single shared comment-id
 * counter every synthetic issue's comments are minted from, so GitHub's
 * "earliest comment id wins" claim protocol (design D13) has a real global
 * total order to race on — exactly as the real GitHub REST API does across
 * an entire repository.
 *
 * <p>Test-only: never shipped, lives entirely under {@code src/test}.
 */
final class FixtureIssueRegistry {

    // TreeMap (synchronized externally by 'this') keeps allIssues() in ascending issue-number
    // order, matching the order synthetic issue numbers are first assigned by the translator —
    // required so listReady's queue-order assertions (readyA before readyB) hold.
    private final Map<Integer, FixtureIssue> issues = new TreeMap<>();
    private final AtomicLong commentIdSequence = new AtomicLong(1);

    // A fixed, ancient base the shared sequence offsets from to mint monotonic comment updated_at
    // instants (see nextUpdatedAt). The exact epoch is irrelevant — a ClaimVersion's updatedAt is
    // only ever compared to another version read back through the port, never to wall-clock time.
    private static final Instant UPDATED_AT_BASE = Instant.parse("2020-01-01T00:00:00Z");

    synchronized FixtureIssue issueFor(int number) {
        return issues.computeIfAbsent(number, FixtureIssue::new);
    }

    /**
     * Whether {@code number} has NEVER been seeded/touched, WITHOUT creating
     * it as a side effect — distinguishes "GitHub has never heard of this
     * issue" (404) from "known issue with no labels yet", needed so {@code
     * fetchTask} on a never-seeded fixture ref correctly observes {@code
     * Gone} rather than a phantom {@code Ready}.
     */
    synchronized boolean isUnknown(int number) {
        return !issues.containsKey(number);
    }

    /** Returns the next comment id in this registry's shared, monotonically increasing sequence. */
    long nextCommentId() {
        return commentIdSequence.getAndIncrement();
    }

    /**
     * Returns the next comment {@code updated_at} instant, drawn from the SAME shared sequence as
     * {@link #nextCommentId()} so every seeded claim and every heartbeat PATCH gets a strictly newer
     * value than any before it — a beat therefore always produces an observably advanced {@link
     * com.github.oinsio.gnomish.app.port.tracker.ClaimVersion}, mirroring GitHub refreshing
     * {@code updated_at} on each in-place comment edit.
     */
    Instant nextUpdatedAt() {
        return UPDATED_AT_BASE.plusSeconds(commentIdSequence.getAndIncrement());
    }

    synchronized Collection<FixtureIssue> allIssues() {
        return List.copyOf(issues.values());
    }
}
