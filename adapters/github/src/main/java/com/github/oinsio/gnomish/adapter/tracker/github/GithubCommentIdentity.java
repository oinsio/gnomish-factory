package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * The hidden content identity that keys the find-then-upsert primitive
 * (design D7, FR11 of harden-task-branch-contract): <em>which task</em> a
 * factory-authored comment belongs to and <em>which intent</em> wrote it —
 * never the account that posted it. Two writes carrying the same identity are
 * the same logical comment, so a crash-retry updates the existing comment in
 * place instead of appending a duplicate report (UX3); two writes that must
 * both survive as separate comments carry different intents.
 *
 * <p>Keying on content rather than on the posting account is deliberate: a
 * bot-account key makes every comment the bot ever wrote a candidate for
 * every upsert (the documented Renovate failure mode), and it breaks the
 * moment a second instance — or a human's token — re-drives the same intent.
 * {@link GithubCommentUpsert} therefore matches on this pair alone.
 *
 * <p>Per NFR-S1 the identity carries only task identity and counters: {@link
 * #of} builds the task part as the repository-scoped {@code
 * owner/repo#number}, deliberately dropping the {@link GithubTaskId} host
 * segment a non-default deployment carries — the comment already lives on
 * that host, so the segment would add a hostname to the wire for no
 * discriminating power. Intents are short protocol words optionally suffixed
 * with a counter; neither part may carry a filesystem path, a hostname, or
 * credential material.
 *
 * <p>Implements FR11, NFR-S1 of harden-task-branch-contract.
 *
 * @param task the repository-scoped task identity, e.g. {@code acme/widgets#42}
 * @param intent the logical write this comment is, e.g. {@code park} or {@code abort#3}
 */
public record GithubCommentIdentity(String task, String intent) {

    public GithubCommentIdentity {
        task = requireIdentityPart(task, "task");
        intent = requireIdentityPart(intent, "intent");
    }

    /**
     * Builds the identity of {@code intent} for the task {@code id} names,
     * scoping the task part to {@code owner/repo#number} (NFR-S1 — see the
     * class Javadoc on the dropped host segment).
     *
     * @param id the task the comment belongs to
     * @param intent the logical write this comment is
     * @return the content identity to stamp into the marker
     */
    static GithubCommentIdentity of(GithubTaskId id, String intent) {
        return new GithubCommentIdentity(id.owner() + "/" + id.repo() + "#" + id.issueNumber(), intent);
    }

    private static String requireIdentityPart(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("comment identity " + field + " must not be blank");
        }
        return value.strip();
    }
}
