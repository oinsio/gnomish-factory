package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements the read side of {@code Tracker.listReady} for the GitHub
 * adapter (github-tracker spec, "Feed via List Issues with PR filtering"):
 * queries the List Issues API — never the Search API — with {@code
 * state=open}, the configured ready label, sorted ascending by creation,
 * filters out entries that are pull requests, and enriches each remaining
 * issue with {@link com.github.oinsio.gnomish.app.port.tracker.AbortFacts}
 * read from its comments (FR8 of add-tracker-port).
 *
 * <p>Both reads go through the shared {@link GithubConditionalRequestCache}
 * (constructor-injected, so the same instance is reused across repeated
 * polls): the feed request itself, and the one additional comments fetch per
 * ready issue that {@link GithubAbortFactsReader#fetchMarkers} does to enrich
 * each entry. So steady-state polling of an unchanged queue of N tasks costs
 * no rate-limit budget — the feed and each unchanged comment thread come back
 * as a {@code 304 Not Modified} reusing the cached body (NFR-P1). The
 * comments-thread ETag is keyed identically to {@code fetchTask}'s, so a claim
 * that immediately re-reads the same issue reuses this enrichment fetch's
 * cached body.
 *
 * <p>Implements FR8 of add-tracker-port; also FR7 and NFR-P1 of
 * add-factory-serve — the adapter-derived "returned" fact each entry
 * carries ({@link GithubHistoryFactReader}) and the conditional-request
 * idle polling the serve feed relies on. The "finished" fact (FR1 of
 * enforce-finish-terminality) is derived from that same reader and the same
 * per-issue comments fetch, so it costs no additional GitHub API call
 * (NFR-P1 of enforce-finish-terminality).
 */
public final class GithubFeedQuery {

    private final GithubConditionalRequestCache cache;
    private final GithubAbortFactsReader abortFactsReader;
    private final String owner;
    private final String repo;
    private final String readyLabel;
    private final String apiUrl;

    /**
     * @param cache the shared conditional-request cache; reused across polls
     *     so this feed query's ETag survives between calls (NFR-P1)
     * @param owner the configured repository owner
     * @param repo the configured repository name
     * @param readyLabel the configured ready-label name (e.g. {@code
     *     gnomish:ready})
     */
    public GithubFeedQuery(GithubConditionalRequestCache cache, String owner, String repo, String readyLabel) {
        this.cache = cache;
        this.abortFactsReader = new GithubAbortFactsReader(cache);
        this.owner = owner;
        this.repo = repo;
        this.readyLabel = readyLabel;
        this.apiUrl = cache.httpClient().apiUrl();
    }

    /**
     * Returns up to {@code limit} ready tasks in ascending-creation queue
     * order, each enriched with {@link
     * com.github.oinsio.gnomish.app.port.tracker.AbortFacts} (FR8, FR1 of
     * add-tracker-port).
     *
     * @param limit the maximum number of entries to return; must be positive
     */
    public List<ReadyTask> listReady(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("listReady limit must be positive, was " + limit);
        }
        String path = "/repos/%s/%s/issues?state=open&labels=%s&sort=created&direction=asc&per_page=100"
                .formatted(owner, repo, URLEncoder.encode(readyLabel, StandardCharsets.UTF_8));
        String cacheKey = "feed:" + owner + "/" + repo;
        var result = cache.get(cache.httpClient().newRequest(path), cacheKey);
        String body =
                switch (result) {
                    case GithubConditionalRequestCache.Fresh fresh -> fresh.body();
                    case GithubConditionalRequestCache.NotModified notModified -> notModified.previousBody();
                };

        List<GithubIssueFeedParser.IssueRef> issues = GithubIssueFeedParser.parseIssues(body);
        List<ReadyTask> readyTasks = new ArrayList<>();
        for (GithubIssueFeedParser.IssueRef issue : issues) {
            if (readyTasks.size() >= limit) {
                break;
            }
            int issueNumber = issue.number();
            TaskRef ref = new TaskRef(
                    GithubTaskId.build(apiUrl, owner, repo, issueNumber).canonicalId());
            // FR7, NFR-P1: one comments fetch per issue, reused for all three facts —
            // no extra GitHub API read for the returned or finished facts.
            List<ParsedMarker> markers = abortFactsReader.fetchMarkers(owner, repo, issueNumber);
            var abortFacts = GithubAbortFactsReader.foldAbortMarkers(markers);
            boolean returned = GithubHistoryFactReader.derive(markers);
            boolean finished = GithubHistoryFactReader.deriveFinished(markers);
            // FR7, NFR-P1 of add-board-command: the title rides the same list-issues response —
            // no per-task fetchTask fan-out.
            readyTasks.add(new ReadyTask(ref, abortFacts, returned, finished, issue.title()));
        }
        return List.copyOf(readyTasks);
    }
}
