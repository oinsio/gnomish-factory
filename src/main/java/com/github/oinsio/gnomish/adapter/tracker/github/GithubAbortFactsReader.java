package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reconstructs a task's {@link AbortFacts} from its GitHub issue comments
 * (design D9, D10): fetches the issue's comments and folds every {@code
 * abort} structural marker into a count and the latest {@code at} timestamp —
 * exactly the facts {@link AbortFacts} carries. This mirrors NFR-R3 ("every
 * coordination fact recoverable from the tracker by a fresh instance"): a
 * feed poll reconstructs abort history from scratch every time, never from
 * adapter-local state.
 *
 * <p>This class does not apply backoff or the K-abort fuse policy — that is
 * core's job over the facts returned here (design D10). It DOES now decide
 * the "since last durable progress" boundary (FR3, design D3 of
 * fix-abort-progress-reset): when a {@link GithubMarkerKind#PROGRESS} marker
 * is present, only ABORT markers strictly after the latest one are folded in,
 * reusing {@link GithubCommentBoundary#latestProgressIndex(List)} and {@link
 * GithubCommentBoundary#foldAbortsAfter(List, int)} — the same PROGRESS-anchor
 * rule {@link GithubCommentBoundary#abortFactsSinceBoundary(List)} applies for
 * {@code fetchTask}. This reader is only called by {@link GithubFeedQuery}
 * for {@code Ready} issues, where no CLAIM/ABORT claim boundary can be active,
 * so the claim-streak fallback in {@code abortFactsSinceBoundary} is
 * irrelevant here — but a PROGRESS marker can still be present on a
 * Ready-adjacent history (PROGRESS is not a claim boundary), so the two
 * readers must agree on it for consistency between {@code listReady} and
 * {@code fetchTask}. Without a PROGRESS marker, every ABORT marker currently
 * on the issue folds in, unchanged from before.
 *
 * <p>{@link #fetchMarkers} is also reused by {@link GithubFeedQuery} to
 * derive the {@code returned} fact ({@link GithubReturnedFactReader}) from
 * the SAME comments fetch, rather than issuing a second GitHub API read for
 * the same thread (NFR-P1 of add-factory-serve: no new GitHub API calls
 * beyond what {@code listReady} already pays for).
 *
 * <p>Implements FR8, FR14 of add-tracker-port; FR3 of
 * fix-abort-progress-reset.
 */
record GithubAbortFactsReader(GithubHttpClient httpClient) {

    /**
     * Fetches the comments of {@code owner/repo#issueNumber} and folds every
     * recognizable {@code abort} structural marker into {@link AbortFacts}.
     *
     * @return {@link AbortFacts#none()} if no abort marker is present
     */
    AbortFacts read(String owner, String repo, int issueNumber) {
        return foldAbortMarkers(fetchMarkers(owner, repo, issueNumber));
    }

    /**
     * Fetches and parses the structural markers from {@code
     * owner/repo#issueNumber}'s comments, without folding them into any
     * particular fact — the shared read {@link GithubFeedQuery} reuses for
     * both {@link AbortFacts} and the {@code returned} fact so the comments
     * thread is fetched only once per issue.
     */
    List<ParsedMarker> fetchMarkers(String owner, String repo, int issueNumber) {
        String path = "/repos/%s/%s/issues/%d/comments?per_page=100".formatted(owner, repo, issueNumber);
        HttpRequest.Builder request = httpClient.newRequest(path).GET();
        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw new GithubFeedQueryException("Failed to fetch comments for %s/%s#%d: HTTP %d"
                    .formatted(owner, repo, issueNumber, response.statusCode()));
        }
        return GithubCommentParser.parseMarkers(response.body());
    }

    static AbortFacts foldAbortMarkers(List<ParsedMarker> markers) {
        Optional<Integer> progressIndex = GithubCommentBoundary.latestProgressIndex(markers);
        if (progressIndex.isPresent()) {
            return GithubCommentBoundary.foldAbortsAfter(markers, progressIndex.get());
        }
        int count = 0;
        Instant lastAbortAt = null;
        for (ParsedMarker marker : markers) {
            if (marker.kind() == GithubMarkerKind.ABORT) {
                count++;
                if (lastAbortAt == null || marker.at().isAfter(lastAbortAt)) {
                    lastAbortAt = marker.at();
                }
            }
        }
        return count == 0 ? AbortFacts.none() : new AbortFacts(count, lastAbortAt);
    }
}
