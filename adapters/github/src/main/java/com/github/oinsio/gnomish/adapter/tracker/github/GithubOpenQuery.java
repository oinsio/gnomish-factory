package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache;
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
 * MISSING is never an error here: the spec requires it be reported with an
 * absent (null) claim, so core policy can decide what that means. The holder is
 * then recovered from the last claim marker still visible in the thread — the
 * last instance to have claimed the task; an issue with the working label but
 * no claim footprint at all (a human mislabel, outside the factory's
 * coordination) has no holder to name and no version to reap, so it is omitted
 * rather than reported with an invented holder.
 *
 * <p>Implements FR5, NFR-P1 of add-claim-heartbeat.
 */
public final class GithubOpenQuery {

    private final GithubConditionalRequestCache cache;
    private final String owner;
    private final String repo;
    private final String workingLabel;
    private final String needsHumanLabel;
    private final String apiUrl;

    /**
     * @param cache the shared conditional-request cache; reused across polls so each open feed's
     *     ETag survives between calls (NFR-P1)
     * @param owner the configured repository owner
     * @param repo the configured repository name
     * @param workingLabel the configured working-label name (e.g. {@code gnomish:working})
     * @param needsHumanLabel the configured needs-human-label name (e.g. {@code gnomish:needs-human})
     */
    public GithubOpenQuery(
            GithubConditionalRequestCache cache,
            String owner,
            String repo,
            String workingLabel,
            String needsHumanLabel) {
        this.cache = cache;
        this.owner = owner;
        this.repo = repo;
        this.workingLabel = workingLabel;
        this.needsHumanLabel = needsHumanLabel;
        this.apiUrl = cache.httpClient().apiUrl();
    }

    /** Implements {@code Tracker.listOpen} for GitHub (FR5, NFR-P1). */
    public List<OpenTask> listOpen() {
        List<OpenTask> open = new ArrayList<>();
        for (GithubIssueFeedParser.IssueRef issue : issueRefs(workingLabel, "open-working")) {
            OpenTask task = workingTask(issue.number(), issue.title());
            if (task != null) {
                open.add(task);
            }
        }
        for (GithubIssueFeedParser.IssueRef issue : issueRefs(needsHumanLabel, "open-needs-human")) {
            open.add(awaitingHumanTask(issue.number(), issue.title()));
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
    private @Nullable OpenTask workingTask(int issueNumber, String title) {
        TaskRef ref = refFor(issueNumber);
        List<GithubClaimComment.Candidate> candidates = GithubClaimComment.parse(fetchComments(issueNumber));
        Optional<GithubClaimComment.Candidate> live = GithubClaimComment.resolve(candidates);
        if (live.isPresent()) {
            GithubClaimComment.Candidate claim = live.get();
            return new OpenTask(
                    ref,
                    new TrackerTaskState.Working(claim.marker().instance()),
                    new ClaimVersion(Long.toString(claim.id()), claim.updatedAt()),
                    title);
        }
        return lastClaimHolder(candidates)
                .map(holder -> new OpenTask(ref, new TrackerTaskState.Working(holder), null, title))
                .orElse(null);
    }

    private OpenTask awaitingHumanTask(int issueNumber, String title) {
        List<ParsedMarker> markers = GithubCommentParser.parseMarkers(fetchComments(issueNumber));
        ParkReason reason = GithubParkReason.latest(markers);
        return new OpenTask(refFor(issueNumber), new TrackerTaskState.AwaitingHuman(reason), null, title);
    }

    /** The instance of the last claim marker still visible in the thread, or empty when none exists. */
    private static Optional<String> lastClaimHolder(List<GithubClaimComment.Candidate> candidates) {
        for (int i = candidates.size() - 1; i >= 0; i--) {
            GithubClaimComment.Candidate candidate = candidates.get(i);
            if (candidate.marker().kind() == GithubMarkerKind.CLAIM) {
                return Optional.of(candidate.marker().instance());
            }
        }
        return Optional.empty();
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
