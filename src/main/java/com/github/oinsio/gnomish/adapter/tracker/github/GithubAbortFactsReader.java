package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

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
 * core's job over the facts returned here (design D10). It also does not
 * decide the "since last durable progress" boundary beyond "every {@code
 * abort} marker currently on the issue": a resumed/claimed task's markers
 * before the claim are still counted here, but {@link GithubFeedQuery} only
 * calls this reader for {@code Ready} issues, where no later boundary marker
 * (claim, ack, finish) can have superseded them — the same fold callers use
 * for {@code fetchTask} (task 4.10) additionally anchor to the latest
 * boundary marker when one exists.
 *
 * <p>Implements FR8, FR14 of add-tracker-port.
 */
record GithubAbortFactsReader(GithubHttpClient httpClient) {

    /**
     * Fetches the comments of {@code owner/repo#issueNumber} and folds every
     * recognizable {@code abort} structural marker into {@link AbortFacts}.
     *
     * @return {@link AbortFacts#none()} if no abort marker is present
     */
    AbortFacts read(String owner, String repo, int issueNumber) {
        String path = "/repos/%s/%s/issues/%d/comments?per_page=100".formatted(owner, repo, issueNumber);
        HttpRequest.Builder request = httpClient.newRequest(path).GET();
        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw new GithubFeedQueryException("Failed to fetch comments for %s/%s#%d: HTTP %d"
                    .formatted(owner, repo, issueNumber, response.statusCode()));
        }
        return foldAbortMarkers(GithubCommentParser.parseMarkers(response.body()));
    }

    private static AbortFacts foldAbortMarkers(List<ParsedMarker> markers) {
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
