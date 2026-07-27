package com.github.oinsio.gnomish.adapter.tracker.github;

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

    synchronized FixtureIssue issueFor(int number) {
        return issues.computeIfAbsent(number, FixtureIssue::new);
    }

    /**
     * Whether {@code number} has ever been seeded/touched, WITHOUT creating
     * it as a side effect — distinguishes "known issue with no labels yet"
     * from "GitHub has never heard of this issue" (404), needed so {@code
     * fetchTask} on a never-seeded fixture ref correctly observes {@code
     * Gone} rather than a phantom {@code Ready}.
     */
    synchronized boolean isKnown(int number) {
        return issues.containsKey(number);
    }

    /** Returns the next comment id in this registry's shared, monotonically increasing sequence. */
    long nextCommentId() {
        return commentIdSequence.getAndIncrement();
    }

    synchronized Collection<FixtureIssue> allIssues() {
        return List.copyOf(issues.values());
    }
}
