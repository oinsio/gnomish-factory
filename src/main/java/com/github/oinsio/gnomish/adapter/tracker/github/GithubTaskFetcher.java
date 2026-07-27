package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.util.List;
import java.util.Locale;
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
 * folding the whole comment history unconditionally, unlike {@link
 * GithubAbortFactsReader}, which is safe to do so only for {@code Ready}
 * issues.
 *
 * <p>A closed issue's {@code state_reason} ({@code completed}/{@code
 * not_planned}/{@code reopened}) is threaded into {@link
 * TrackerTaskState.Gone#closureReason()} so it reaches the revocation context,
 * as the github-tracker spec requires ("issue closure (= revocation), with
 * {@code state_reason} included in revocation context"). A merely nonexistent
 * issue (a 404) has no reason and yields the no-arg {@link
 * TrackerTaskState.Gone}.
 *
 * <p>Judgment call (task 4.10): design D9's marker-kind vocabulary has no
 * {@code park} kind. A park is represented as a {@code report}-kind marker
 * whose structural JSON carries an additional {@code reason} field (see
 * {@link GithubMarker#render(GithubMarkerKind, String, java.time.Instant,
 * String, String)}), holding the lowercase wire value of a {@link
 * ParkReason}. Task 4.14 (the {@code park} write path) MUST post this same
 * field for {@code fetchTask} to keep recovering the reason correctly.
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
 */
public record GithubTaskFetcher(GithubConditionalRequestCache cache, String workingLabel, String needsHumanLabel) {

    /** Implements {@code Tracker.fetchTask} for GitHub (FR2, FR5). */
    public TrackerTask fetchTask(TaskRef ref) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        Optional<GithubIssueDetail> issue = fetchIssueDetail(ref);
        if (issue.isEmpty()) {
            return new TrackerTask(ref, goneSnapshot(ref), new TrackerTaskState.Gone(), AbortFacts.none());
        }
        GithubIssueDetail detail = issue.get();
        if (detail.isClosed()) {
            return new TrackerTask(
                    ref, goneSnapshot(ref), new TrackerTaskState.Gone(detail.stateReason()), AbortFacts.none());
        }
        TaskSnapshot snapshot = new TaskSnapshot(ref.id(), detail.title(), detail.bodyOrEmpty());

        List<ParsedMarker> markers = fetchMarkers(id.owner(), id.repo(), id.issueNumber());
        AbortFacts abortFacts = GithubCommentBoundary.abortFactsSinceBoundary(markers);
        TrackerTaskState state = stateFrom(detail, markers);
        return new TrackerTask(ref, snapshot, state, abortFacts);
    }

    private TrackerTaskState stateFrom(GithubIssueDetail detail, List<ParsedMarker> markers) {
        if (detail.labelNames().contains(workingLabel)) {
            String holder = GithubCommentBoundary.activeClaim(markers)
                    .map(ParsedMarker::instance)
                    .orElseThrow(() -> new GithubFeedQueryException(
                            "issue carries the working label but no active claim marker was found"));
            return new TrackerTaskState.Working(holder);
        }
        if (detail.labelNames().contains(needsHumanLabel)) {
            return new TrackerTaskState.AwaitingHuman(latestParkReason(markers));
        }
        return new TrackerTaskState.Ready();
    }

    private static ParkReason latestParkReason(List<ParsedMarker> markers) {
        for (int i = markers.size() - 1; i >= 0; i--) {
            ParsedMarker marker = markers.get(i);
            if (marker.kind() == GithubMarkerKind.REPORT && marker.reason() != null) {
                return parseParkReason(marker.reason());
            }
        }
        throw new GithubFeedQueryException(
                "issue carries the needs-human label but no report marker with a reason was found");
    }

    private static ParkReason parseParkReason(String wireValue) {
        return ParkReason.valueOf(wireValue.toUpperCase(Locale.ROOT));
    }

    /**
     * Returns empty for a 404 (missing issue); never throws for that case. Goes through the
     * conditional cache, so an unchanged issue re-read at a round boundary is a free {@code 304}.
     */
    private Optional<GithubIssueDetail> fetchIssueDetail(TaskRef ref) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
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

    private List<ParsedMarker> fetchMarkers(String owner, String repo, int issueNumber) {
        String path = "/repos/%s/%s/issues/%d/comments?per_page=100".formatted(owner, repo, issueNumber);
        String cacheKey = "comments:%s/%s#%d".formatted(owner, repo, issueNumber);
        String body =
                switch (cache.get(cache.httpClient().newRequest(path), cacheKey)) {
                    case GithubConditionalRequestCache.NotModified notModified -> notModified.previousBody();
                    case GithubConditionalRequestCache.Fresh fresh -> {
                        if (fresh.statusCode() / 100 != 2) {
                            throw new GithubFeedQueryException("Failed to fetch comments for %s/%s#%d: HTTP %d"
                                    .formatted(owner, repo, issueNumber, fresh.statusCode()));
                        }
                        yield fresh.body();
                    }
                };
        return GithubCommentParser.parseMarkers(body);
    }

    private static TaskSnapshot goneSnapshot(TaskRef ref) {
        return new TaskSnapshot(ref.id(), ref.id(), "");
    }
}
