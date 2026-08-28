package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements {@code Tracker.listOpen} for the GitHub adapter (github-tracker
 * spec, "Open-task listing via state labels with conditional requests"): the
 * live open tasks — {@code Working} and {@code AwaitingHuman} — each with its
 * logical state and, for a {@code Working} task, the claim facts (holder and
 * the {@code (comment id, updated_at)} version).
 *
 * <p>GitHub's {@code labels=a,b} filter is AND, so the two open states can not
 * be read in one call — this issues TWO List Issues queries, one per state
 * label, each with a DISTINCT cache key through the shared {@link
 * GithubConditionalRequestCache}: an unchanged poll of either feed is a free
 * {@code 304} (NFR-P1). Pull-request entries are excluded exactly as the ready
 * feed does, via {@link GithubIssueFeedParser}. This is the List Issues API,
 * never the Search API, matching {@link GithubFeedQuery}.
 *
 * <p>Each {@code Working} issue's claim comment is resolved with the shared
 * {@link GithubClaimComment} resolver (the same earliest-id-since-boundary
 * anchor {@code heartbeat} beats), read through the same {@code comments:…}
 * cache key {@link GithubTaskFetcher} uses so both share the thread's ETag.
 * Unlike {@code fetchTask}, a {@code Working}-labeled issue whose live claim is
 * MISSING is never an error here, and never an omission either (FR19 of
 * harden-task-branch-contract): every entry is reported with the raw {@link TrackerFacts} it
 * observed — the labels present, the claim footprint (live, dead, or none), and the newest boundary
 * marker after that footprint — and the core classifier alone decides what the combination means.
 * The earlier rule that dropped a working-labeled issue with no claim footprint as a human mislabel
 * is exactly what hid the claim sequence's own kill window from every sweep.
 *
 * <p>Implements FR5, NFR-P1 of add-claim-heartbeat; FR19 of harden-task-branch-contract.
 */
public final class GithubOpenQuery {

    private final GithubConditionalRequestCache cache;
    private final String owner;
    private final String repo;
    private final GithubStateLabels labels;
    private final String workingLabel;
    private final String needsHumanLabel;
    private final String apiUrl;

    /**
     * @param cache the shared conditional-request cache; reused across polls so each open feed's
     *     ETag survives between calls (NFR-P1)
     * @param owner the configured repository owner
     * @param repo the configured repository name
     * @param labels the four configured label names, for the presence facts each entry reports
     */
    public GithubOpenQuery(GithubConditionalRequestCache cache, String owner, String repo, GithubStateLabels labels) {
        this.cache = cache;
        this.owner = owner;
        this.repo = repo;
        this.labels = labels;
        this.workingLabel = labels.working();
        this.needsHumanLabel = labels.needsHuman();
        this.apiUrl = cache.httpClient().apiUrl();
    }

    /** Implements {@code Tracker.listOpen} for GitHub (FR5, NFR-P1). */
    public List<OpenTask> listOpen() {
        List<OpenTask> open = new ArrayList<>();
        for (GithubIssueFeedParser.IssueRef issue : issueRefs(workingLabel, "open-working")) {
            open.add(workingTask(issue));
        }
        for (GithubIssueFeedParser.IssueRef issue : issueRefs(needsHumanLabel, "open-needs-human")) {
            open.add(awaitingHumanTask(issue));
        }
        return List.copyOf(open);
    }

    private List<GithubIssueFeedParser.IssueRef> issueRefs(String label, String cacheKeyPrefix) {
        String path = "/repos/%s/%s/issues?state=open&labels=%s&sort=created&direction=asc&per_page=100"
                .formatted(owner, repo, URLEncoder.encode(label, StandardCharsets.UTF_8));
        String cacheKey = cacheKeyPrefix + ":" + owner + "/" + repo;
        String body =
                switch (cache.get(cache.httpClient().newRequest(path), cacheKey)) {
                    case GithubConditionalRequestCache.NotModified notModified -> notModified.previousBody();
                    case GithubConditionalRequestCache.Fresh fresh -> {
                        if (fresh.statusCode() / 100 != 2) {
                            throw new GithubFeedQueryException("Failed to fetch open %s issues for %s/%s: HTTP %d"
                                    .formatted(label, owner, repo, fresh.statusCode()));
                        }
                        yield fresh.body();
                    }
                };
        return GithubIssueFeedParser.parseIssues(body);
    }

    // FR7, NFR-P1 of add-board-command: title rides the same list-issues response that named this
    //     issue's number — no per-task fetchTask fan-out.
    private OpenTask workingTask(GithubIssueFeedParser.IssueRef issue) {
        TaskRef ref = refFor(issue.number());
        List<GithubClaimComment.Candidate> candidates = GithubClaimComment.parse(fetchComments(issue.number()));
        TrackerFacts facts = GithubTrackerFacts.of(labels.observed(issue.labels()), candidates);
        // A working-labeled issue with no live claim is reported, never omitted (FR19): the holder
        // named on the entry is the footprint's own — the live claim's, or the last-known one of a
        // dead footprint — and an absent footprint yields no holder at all rather than an invented
        // one. The core classifier alone decides what each combination means.
        String holder = facts.claim().holder();
        return new OpenTask(
                ref,
                new TrackerTaskState.Working(holder == null ? GithubTrackerFacts.UNKNOWN_HOLDER : holder),
                facts.claim().liveVersion(),
                issue.title(),
                facts);
    }

    private OpenTask awaitingHumanTask(GithubIssueFeedParser.IssueRef issue) {
        List<GithubClaimComment.Candidate> candidates = GithubClaimComment.parse(fetchComments(issue.number()));
        List<ParsedMarker> markers =
                candidates.stream().map(GithubClaimComment.Candidate::marker).toList();
        ParkReason reason = GithubParkReason.latest(markers);
        TrackerFacts facts = GithubTrackerFacts.of(labels.observed(issue.labels()), candidates);
        return new OpenTask(
                refFor(issue.number()),
                new TrackerTaskState.AwaitingHuman(reason),
                facts.claim().liveVersion(),
                issue.title(),
                facts);
    }

    private String fetchComments(int issueNumber) {
        String path = "/repos/%s/%s/issues/%d/comments?per_page=100".formatted(owner, repo, issueNumber);
        String cacheKey = "comments:%s/%s#%d".formatted(owner, repo, issueNumber);
        return switch (cache.get(cache.httpClient().newRequest(path), cacheKey)) {
            case GithubConditionalRequestCache.NotModified notModified -> notModified.previousBody();
            case GithubConditionalRequestCache.Fresh fresh -> {
                if (fresh.statusCode() / 100 != 2) {
                    throw new GithubFeedQueryException("Failed to fetch comments for %s/%s#%d: HTTP %d"
                            .formatted(owner, repo, issueNumber, fresh.statusCode()));
                }
                yield fresh.body();
            }
        };
    }

    private TaskRef refFor(int issueNumber) {
        return new TaskRef(GithubTaskId.build(apiUrl, owner, repo, issueNumber).canonicalId());
    }
}
