package com.github.oinsio.gnomish.adapter.tracker.github;

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
 * <p>The feed query itself goes through the shared {@link
 * GithubConditionalRequestCache} (constructor-injected, so the same instance
 * is reused across repeated polls), so steady-state polling of an unchanged
 * queue costs no rate-limit budget (NFR-P1). Abort-fact enrichment requires
 * one additional comments fetch per ready issue — acceptable for v1; a future
 * optimization could cache or batch these per NFR-P1 if issue volume grows.
 *
 * <p>Implements FR8 of add-tracker-port.
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
        this.abortFactsReader = new GithubAbortFactsReader(cache.httpClient());
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

        List<Integer> issueNumbers = GithubIssueFeedParser.parseIssueNumbers(body);
        List<ReadyTask> readyTasks = new ArrayList<>();
        for (int issueNumber : issueNumbers) {
            if (readyTasks.size() >= limit) {
                break;
            }
            TaskRef ref = new TaskRef(
                    GithubTaskId.build(apiUrl, owner, repo, issueNumber).canonicalId());
            var abortFacts = abortFactsReader.read(owner, repo, issueNumber);
            readyTasks.add(new ReadyTask(ref, abortFacts));
        }
        return List.copyOf(readyTasks);
    }
}
