package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache;
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.util.List;
import java.util.Optional;

/**
 * Implements {@code Tracker.fetchTask} for the GitHub adapter (github-tracker
 * spec, "Logical states map to mutually exclusive labels"; tracker-port spec,
 * "Task facts from fetchTask"): fetches the issue, reports {@link
 * TrackerTaskState.Gone} for a closed or missing issue without throwing,
 * otherwise derives the logical state from the configured working/needs-human
 * label names and boundary-anchors the claim holder, park reason, and abort
 * facts to the latest {@code claim}/{@code abort} structural marker (design
 * D13's boundary-anchoring idea, via {@link GithubCommentBoundary}) — never
 * folding the whole comment history unconditionally, unlike the unconditional
 * fold {@link GithubAbortFactsReader#foldAbortMarkers} performs, which is safe
 * to do so only for {@code Ready} issues. The comment fetch itself is shared:
 * this fetcher delegates to {@link GithubAbortFactsReader#fetchMarkers} rather
 * than duplicating the conditional-request read, so both readers stay on the
 * same cache key and parsing logic.
 *
 * <p>A closed issue's {@code state_reason} ({@code completed}/{@code
 * not_planned}/{@code reopened}) is threaded into {@link
 * TrackerTaskState.Gone#closureReason()} so it reaches the revocation context,
 * as the github-tracker spec requires ("issue closure (= revocation), with
 * {@code state_reason} included in revocation context"). A merely nonexistent
 * issue (a 404) has no reason and yields the no-arg {@link
 * TrackerTaskState.Gone}.
 *
 * <p>A park is represented as a dedicated {@code PARK}-kind marker (design D9,
 * refined by enforce-finish-terminality task 3.1's split of the former single
 * {@code REPORT} kind into {@code PARK} and {@code FINISH}) whose structural
 * JSON carries an additional {@code reason} field (see {@link
 * GithubMarker#render(GithubMarkerKind, String, java.time.Instant, String,
 * String)}), holding the lowercase wire value of a {@link ParkReason}. {@link
 * GithubStateWrites#park} MUST post this same field for {@code fetchTask} to
 * keep recovering the reason correctly.
 *
 * <p>{@code finished} is derived the same way {@link GithubFeedQuery} derives
 * it for {@code listReady} — via {@link
 * GithubHistoryFactReader#deriveFinished} over the same fetched {@code
 * markers}, true iff the thread carries a {@code FINISH} structural marker
 * anywhere in its history — never from adapter-local state (design D1, D2,
 * FR1 of enforce-finish-terminality). A {@link TrackerTaskState.Gone} result
 * (issue closed or missing) reports {@code finished = false} unconditionally,
 * without fetching comments for that branch: a gone issue has no meaningful
 * finished fact from this read.
 *
 * <p>Both reads {@code fetchTask} makes — the issue and its comments — go
 * through the shared {@link GithubConditionalRequestCache}, so the round-boundary
 * revocation check (FR15) that re-reads an unchanged task pays no rate-limit
 * budget: an unchanged issue and unchanged comment thread each come back as a
 * {@code 304 Not Modified} reusing the cached body (NFR-P1). The two reads use
 * distinct cache keys ({@code issue:…} and {@code comments:…}) so the issue's
 * ETag and the comment thread's ETag are tracked independently — the labels can
 * change (a human parks the task) while the comments do not, and vice versa.
 *
 * <p>Implements FR2, FR5, FR15, NFR-P1 of add-tracker-port.
 *
 * @param cache the shared conditional-request cache; reused across polls so this fetcher's
 *     per-issue and per-comment-thread ETags survive between round-boundary checks (NFR-P1)
 * @param workingLabel the configured working-label name (e.g. {@code gnomish:working})
 * @param needsHumanLabel the configured needs-human-label name (e.g. {@code gnomish:needs-human})
 * @param deliveredLabel the configured delivered-label name (e.g. {@code gnomish:delivered}), mapped to
 *     {@link TrackerTaskState.Finished}
 */
public record GithubTaskFetcher(
        GithubConditionalRequestCache cache, String workingLabel, String needsHumanLabel, String deliveredLabel) {

    /** Implements {@code Tracker.fetchTask} for GitHub (FR2, FR5). */
    public TrackerTask fetchTask(TaskRef ref) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        Optional<GithubIssueDetail> issue = fetchIssueDetail(id);
        if (issue.isEmpty()) {
            return new TrackerTask(ref, goneSnapshot(ref), new TrackerTaskState.Gone(), AbortFacts.none(), false);
        }
        GithubIssueDetail detail = issue.get();
        if (detail.isClosed()) {
            return new TrackerTask(
                    ref, goneSnapshot(ref), new TrackerTaskState.Gone(detail.stateReason()), AbortFacts.none(), false);
        }
        TaskSnapshot snapshot = new TaskSnapshot(ref.id(), detail.title(), detail.bodyOrEmpty());

        List<ParsedMarker> markers =
                new GithubAbortFactsReader(cache).fetchMarkers(id.owner(), id.repo(), id.issueNumber());
        AbortFacts abortFacts = GithubCommentBoundary.abortFactsSinceBoundary(markers);
        TrackerTaskState state = stateFrom(detail, markers);
        boolean finished = GithubHistoryFactReader.deriveFinished(markers);
        return new TrackerTask(ref, snapshot, state, abortFacts, finished);
    }

    /** The instance of the last claim marker in the thread, or the placeholder when there is none. */
    private static String lastClaimHolder(List<ParsedMarker> markers) {
        for (int i = markers.size() - 1; i >= 0; i--) {
            if (markers.get(i).kind() == GithubMarkerKind.CLAIM) {
                return markers.get(i).instance();
            }
        }
        return GithubTrackerFacts.UNKNOWN_HOLDER;
    }

    private TrackerTaskState stateFrom(GithubIssueDetail detail, List<ParsedMarker> markers) {
        if (detail.labelNames().contains(workingLabel)) {
            // FR19 of harden-task-branch-contract: a working label with no active claim marker is a
            // fact, not an error — it is the claim sequence's own kill window, and a fetch that
            // threw on it turned a swept, repairable shape into a crash on every reader's path.
            // The state names the last instance to have claimed the task, or the placeholder when
            // the thread names none; the sweep classifies the same issue off its listing facts.
            String holder = GithubCommentBoundary.activeClaim(markers)
                    .map(ParsedMarker::instance)
                    .orElseGet(() -> lastClaimHolder(markers));
            return new TrackerTaskState.Working(holder);
        }
        if (detail.labelNames().contains(needsHumanLabel)) {
            return new TrackerTaskState.AwaitingHuman(GithubParkReason.latest(markers));
        }
        if (detail.labelNames().contains(deliveredLabel)) {
            return new TrackerTaskState.Finished();
        }
        return new TrackerTaskState.Ready();
    }

    /**
     * Returns empty for a 404 (missing issue); never throws for that case. Goes through the
     * conditional cache, so an unchanged issue re-read at a round boundary is a free {@code 304}.
     */
    private Optional<GithubIssueDetail> fetchIssueDetail(GithubTaskId id) {
        String path = "/repos/%s/%s/issues/%d".formatted(id.owner(), id.repo(), id.issueNumber());
        String cacheKey = "issue:%s/%s#%d".formatted(id.owner(), id.repo(), id.issueNumber());
        return switch (cache.get(cache.httpClient().newRequest(path), cacheKey)) {
            case GithubConditionalRequestCache.NotModified notModified ->
                Optional.of(GithubIssueDetailParser.parse(notModified.previousBody()));
            case GithubConditionalRequestCache.Fresh fresh -> {
                if (fresh.statusCode() == 404) {
                    yield Optional.empty();
                }
                if (fresh.statusCode() / 100 != 2) {
                    throw new GithubFeedQueryException("Failed to fetch issue %s/%s#%d: HTTP %d"
                            .formatted(id.owner(), id.repo(), id.issueNumber(), fresh.statusCode()));
                }
                yield Optional.of(GithubIssueDetailParser.parse(fresh.body()));
            }
        };
    }

    private static TaskSnapshot goneSnapshot(TaskRef ref) {
        return new TaskSnapshot(ref.id(), ref.id(), "");
    }
}
