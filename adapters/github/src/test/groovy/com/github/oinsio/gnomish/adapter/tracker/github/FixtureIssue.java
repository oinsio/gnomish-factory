package com.github.oinsio.gnomish.adapter.tracker.github;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only mutable state for one synthetic GitHub issue backing the
 * WireMock-dynamic contract harness (task 4.16): current label set, closed
 * flag, and the full ordered comment list (claim/abort/ack/report markers
 * plus plain human replies), all mutated concurrently by racing {@code
 * claim()} callers and read back by the WireMock transformers that answer
 * "get issue" / "list comments" / "list issues" requests.
 *
 * <p>This is test of infrastructure only (never shipped): it exists solely to
 * let the real production {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubTracker} run
 * unmodified against WireMock while its own mock responses reflect prior
 * mock traffic — the "WireMock's response must reflect prior WireMock
 * traffic" need documented in task 4.16.
 */
final class FixtureIssue {

    private final int number;
    private final CopyOnWriteArrayList<String> labels = new CopyOnWriteArrayList<>();
    private final List<FixtureComment> comments = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<String> title = new AtomicReference<>("fixture title");
    private final AtomicReference<String> body = new AtomicReference<>("fixture body");

    FixtureIssue(int number) {
        this.number = number;
    }

    int number() {
        return number;
    }

    void addLabel(String label) {
        // addIfAbsent mirrors GitHub, where a label is present at most once: two racing
        // removeStaleClaim callers each re-add the ready label (working→ready transition),
        // and GitHub coalesces that to a single label rather than a duplicate.
        labels.addIfAbsent(label);
    }

    void removeLabel(String label) {
        labels.remove(label);
    }

    List<String> labels() {
        return List.copyOf(labels);
    }

    void close() {
        closed.set(true);
    }

    boolean isClosed() {
        return closed.get();
    }

    void title(String value) {
        title.set(value);
    }

    String title() {
        return title.get();
    }

    void body(String value) {
        body.set(value);
    }

    String body() {
        return body.get();
    }

    /** Appends a comment posted "now" with the given (monotonically increasing) comment id. */
    void appendComment(String rawBody, long commentId) {
        appendComment(rawBody, commentId, Instant.now());
    }

    /** Appends a comment with an explicit {@code createdAt} (e.g. a seeded human reply's own posting time). */
    void appendComment(String rawBody, long commentId, Instant createdAt) {
        appendComment(rawBody, commentId, createdAt, createdAt);
    }

    /**
     * Appends a comment with distinct {@code createdAt}/{@code updatedAt} — a seeded claim comment
     * carries an {@code updatedAt} advanced past its creation so its {@code ClaimVersion} is a live,
     * beatable fact (a beat then refreshes {@code updatedAt} again via {@link #patchComment}).
     */
    void appendComment(String rawBody, long commentId, Instant createdAt, Instant updatedAt) {
        comments.add(new FixtureComment(commentId, rawBody, createdAt, updatedAt));
    }

    /**
     * Refreshes a comment's body and {@code updatedAt} in place (GitHub's "edit comment"), keeping
     * its id and {@code createdAt} — the heartbeat PATCH path. Returns whether the comment was found;
     * a miss means it was already deleted (a reaper won the race), which the caller maps to a 404.
     */
    boolean patchComment(long commentId, String newBody, Instant newUpdatedAt) {
        for (int i = 0; i < comments.size(); i++) {
            FixtureComment c = comments.get(i);
            if (c.id() == commentId) {
                comments.set(i, new FixtureComment(commentId, newBody, c.createdAt(), newUpdatedAt));
                return true;
            }
        }
        return false;
    }

    void removeComment(long commentId) {
        comments.removeIf(c -> c.id() == commentId);
    }

    List<FixtureComment> comments() {
        return List.copyOf(comments);
    }

    /** One raw comment as GitHub would report it: id, body, posting time, and last-edit time, in posting order. */
    record FixtureComment(long id, String body, Instant createdAt, Instant updatedAt) {}
}
