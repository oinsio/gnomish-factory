package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Populates one {@link FixtureIssue}'s labels and comments so that {@link
 * com.github.oinsio.gnomish.adapter.tracker.github.GithubTaskFetcher} (the
 * real, unmodified production class) derives the exact {@link
 * TrackerTaskState}/{@link AbortFacts} the {@code TrackerContract} suite's
 * {@code seedTask}/{@code seedReply} calls request (task 4.16). Split out of
 * {@link GithubTrackerFixtureAdapter} to keep that class focused on ref
 * translation; this class owns only the wire-shape knowledge (label names,
 * marker kinds, boundary ordering) needed to build a fixture issue's state.
 *
 * <p>Every marker this class posts uses a synthetic, strictly-increasing
 * timestamp — a fixed base instant offset by seconds, NEVER wall-clock
 * {@code Instant.now()} — because {@link GithubCommentBoundary} decides
 * "latest boundary" by each marker's OWN {@code at} field, not by list/
 * comment-posting order. The base is deliberately an ancient, fixed instant
 * (year 2000) rather than "now minus a margin": a contract row can post a
 * LATER production write carrying an arbitrary caller-supplied historical
 * timestamp of its own (e.g. {@code TrackerMarkerContract}'s {@code
 * recordAbort} call uses a fixed {@code 2026-07-20T10:00:00Z} regardless of
 * wall-clock "now"), so only a base far earlier than every fixture date in
 * the suite (all 2026 or later) can guarantee every seeded marker still
 * sorts chronologically before it.
 *
 * <p>Test-only: never shipped.
 */
record FixtureSeeder(FixtureIssueRegistry registry, String instanceId) {

    /** Default GitHub label names, matching the github-tracker spec's defaults. */
    static final String READY_LABEL = "gnomish:ready";

    static final String WORKING_LABEL = "gnomish:working";
    static final String NEEDS_HUMAN_LABEL = "gnomish:needs-human";
    static final String DELIVERED_LABEL = "gnomish:delivered";

    /**
     * Base instant the auto-ticked synthetic clock ({@link #postMarker(FixtureIssue,
     * GithubMarkerKind, String, String, String)}) offsets from — a fixed, ancient
     * instant guaranteed earlier than any fixture timestamp the contract suite uses
     * (see class Javadoc for why "now minus a margin" is not safe here).
     */
    private static final Instant SEED_CLOCK_BASE = Instant.parse("2000-01-01T00:00:00Z");

    /** Seeds {@code issue} to observably produce {@code state} with {@code abortFacts} via fetchTask. */
    void seedTask(FixtureIssue issue, TrackerTaskState state, AbortFacts abortFacts) {
        switch (state) {
            case TrackerTaskState.Ready ignored -> {
                issue.addLabel(READY_LABEL);
                seedAbortHistory(issue, abortFacts, instanceId);
            }
            case TrackerTaskState.Working working -> {
                issue.addLabel(WORKING_LABEL);
                // Abort history for a Working task represents attempts BEFORE the current,
                // still-active claim ("aborts since last durable progress", carried forward into
                // this claim per the tracker-port spec's AbortFacts semantics). The ABORT markers
                // must (a) precede the CLAIM marker, so GithubCommentBoundary's boundary-anchored
                // claim holder still finds an active claim, and (b) be posted by the SAME holder
                // as the active claim, so GithubCommentBoundary's retry-streak fold (which stops
                // at a different instance's claim, matching "boundary-anchors the claim holder" in
                // GithubTaskFetcherSpec) counts them as this holder's own retry history. The CLAIM
                // marker's own timestamp must therefore be pinned explicitly after the abort
                // streak's lastAbortAt when one is seeded — the auto/synthetic clock (year 2000)
                // would otherwise sort BEFORE a fixture lastAbortAt in the 2020s+.
                seedAbortHistory(issue, abortFacts, working.holder());
                Instant claimAt = abortFacts.count() == 0
                        ? SEED_CLOCK_BASE.plusSeconds(registry.nextCommentId())
                        : requireLastAbortAt(abortFacts).plusSeconds(1);
                postMarker(issue, GithubMarkerKind.CLAIM, working.holder(), null, claimAt, "claimed by " + working.holder());
            }
            case TrackerTaskState.AwaitingHuman awaitingHuman -> {
                issue.addLabel(NEEDS_HUMAN_LABEL);
                String reasonWire = awaitingHuman.reason().name().toLowerCase(Locale.ROOT);
                postMarker(issue, GithubMarkerKind.PARK, instanceId, reasonWire, "awaiting human: " + reasonWire);
            }
            case TrackerTaskState.Finished ignored -> issue.addLabel(DELIVERED_LABEL);
            case TrackerTaskState.Gone ignored -> issue.close();
        }
    }

    /**
     * Seeds {@code issue} to {@code Working(holder)} WITH a live, resolvable claim comment, per
     * {@code TrackerLeaseContract.seedWorkingWithClaim}. Unlike {@link #seedTask}'s {@code Working}
     * branch (which pins the CLAIM's {@code at} against a seeded abort streak), this seeds a clean
     * claim with no abort history: the working label plus one CLAIM marker carrying a real comment
     * id and an advanced {@code updated_at}, so {@code GithubClaimComment} resolves a non-null {@link
     * com.github.oinsio.gnomish.app.port.tracker.ClaimVersion} that {@code heartbeat} can beat and
     * {@code removeStaleClaim} can reap. The comment id is minted from the shared sequence (its
     * earliest-id-since-boundary total order), and the {@code updated_at} from {@link
     * FixtureIssueRegistry#nextUpdatedAt()} so a later beat reads a strictly newer version.
     */
    void seedWorkingWithClaim(FixtureIssue issue, String holder) {
        issue.addLabel(WORKING_LABEL);
        long id = registry.nextCommentId();
        Instant at = SEED_CLOCK_BASE.plusSeconds(id);
        String body = GithubMarker.render(GithubMarkerKind.CLAIM, holder, at, "🤖 gnomish: claimed by " + holder);
        issue.appendComment(body, id, at, registry.nextUpdatedAt());
    }

    /** Seeds a pending human reply comment, per {@code TrackerContract.seedReply}. */
    void seedReply(FixtureIssue issue, HumanReply reply) {
        long id = registry.nextCommentId();
        issue.appendComment(reply.body(), id, reply.postedAt());
    }

    private void seedAbortHistory(FixtureIssue issue, AbortFacts abortFacts, String actor) {
        if (abortFacts.count() == 0) {
            return;
        }
        // Every marker's 'at' must be <= lastAbortAt, and the LAST-posted marker's 'at' must
        // equal it exactly (the boundary fold reports the latest ABORT marker's own timestamp
        // verbatim) — so earlier markers get strictly earlier synthetic timestamps.
        Instant last = requireLastAbortAt(abortFacts);
        int count = abortFacts.count();
        for (int i = 0; i < count; i++) {
            Instant at = i == count - 1 ? last : last.minusSeconds(count - i);
            postMarker(issue, GithubMarkerKind.ABORT, actor, null, at, "aborted (fixture)");
        }
    }

    /** Asserts the {@code count > 0 ⇒ non-null lastAbortAt} pairing the seeder relies on. */
    private static Instant requireLastAbortAt(AbortFacts abortFacts) {
        return Objects.requireNonNull(
                abortFacts.lastAbortAt(), "positive abort count must carry a non-null lastAbortAt");
    }

    /** Posts a marker with the next tick of this seeder's synthetic clock, always earlier than any wall-clock write. */
    private void postMarker(FixtureIssue issue, GithubMarkerKind kind, String actor, String reason, String humanText) {
        postMarker(issue, kind, actor, reason, SEED_CLOCK_BASE.plusSeconds(registry.nextCommentId()), humanText);
    }

    private void postMarker(
            FixtureIssue issue, GithubMarkerKind kind, String actor, String reason, Instant at, String humanText) {
        long id = registry.nextCommentId();
        String body = reason == null
                ? GithubMarker.render(kind, actor, at, humanText)
                : GithubMarker.render(kind, actor, at, humanText, reason);
        issue.appendComment(body, id);
    }
}
