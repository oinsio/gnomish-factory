package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind;
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult;
import com.github.oinsio.gnomish.app.port.tracker.StateLabels;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

/**
 * Implements {@code Tracker.repairIndex} for the GitHub adapter (github-tracker spec, "Index-repair
 * physics"): brings an issue's labels to the state its recorded truth implies, with point label
 * calls and no claim of its own.
 *
 * <p>Two observed shapes reach here, and the facts alone decide which physics runs — the adapter
 * never re-classifies. A working label with a boundary marker after the newest claim is a lagging
 * index: the labels flip to the pair that boundary implies (ready for an abort or a stale-claim
 * removal, needs-human for a park, delivered for a finish), and no work is re-executed. A working
 * label with no boundary is a claim that never posted its marker: the labels roll back to ready.
 *
 * <p>The repair marker posts before the flips, so the record of the repair exists even if a later
 * step fails. It is deliberately not a claim boundary ({@link GithubMarkerKind#INDEX_REPAIR}): it
 * implies no state of its own, so it must never displace the boundary whose flip it is completing —
 * a kill between the marker and the flips leaves the very same observed shape, and the next sweep
 * tick runs the same repair again.
 *
 * <p>Before acting it re-reads the labels and the thread and compares the facts against the
 * caller's observation; anything else is {@link RepairIndexResult.Unchanged} with the current
 * facts, which is what makes concurrent repairs converge.
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 */
// Not a record: a behavior-bearing repair service holding an HTTP client, label ops and the marker
// writer, kept as a plain final class for parity with its siblings.
@SuppressWarnings("ClassCanBeRecord")
public final class GithubIndexRepair {

    private final GithubHttpClient httpClient;
    private final GithubLabelOps labelOps;
    private final GithubMarkerWriter markerWriter;
    private final GithubStateLabels labels;

    public GithubIndexRepair(
            GithubHttpClient httpClient,
            GithubLabelOps labelOps,
            GithubMarkerWriter markerWriter,
            GithubStateLabels labels) {
        this.httpClient = httpClient;
        this.labelOps = labelOps;
        this.markerWriter = markerWriter;
        this.labels = labels;
    }

    /** Implements {@code Tracker.repairIndex} for GitHub (FR19, FR12). */
    public RepairIndexResult repairIndex(TaskRef ref, TrackerFacts observedFacts) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        TrackerFacts current = reRead(id);
        if (!current.equals(observedFacts)) {
            return new RepairIndexResult.Unchanged(current);
        }
        postRepairMarker(id, current);
        flip(id, current.latestBoundary());
        return new RepairIndexResult.Repaired(reRead(id));
    }

    /** Freshly re-reads the issue's labels and thread — never through the conditional cache. */
    private TrackerFacts reRead(GithubTaskId id) {
        StateLabels observed = labels.observed(issueLabelNames(id));
        return GithubTrackerFacts.of(observed, GithubClaimComment.parse(comments(id)));
    }

    private List<String> issueLabelNames(GithubTaskId id) {
        String path = "/repos/%s/%s/issues/%d".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpResponse<String> response =
                httpClient.send(httpClient.newRequest(path).GET());
        if (response.statusCode() / 100 != 2) {
            throw new GithubIndexRepairException("Failed to re-read issue %s/%s#%d: HTTP %d"
                    .formatted(id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
        return GithubIssueDetailParser.parse(response.body()).labelNames();
    }

    private String comments(GithubTaskId id) {
        String path = "/repos/%s/%s/issues/%d/comments?per_page=100".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpResponse<String> response =
                httpClient.send(httpClient.newRequest(path).GET());
        if (response.statusCode() / 100 != 2) {
            throw new GithubIndexRepairException("Failed to re-read comments for %s/%s#%d: HTTP %d"
                    .formatted(id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
        return response.body();
    }

    private void postRepairMarker(GithubTaskId id, TrackerFacts observed) {
        String shape = observed.latestBoundary() == null
                ? "claim pending"
                : "index lagging behind the " + observed.latestBoundary().name().toLowerCase(java.util.Locale.ROOT)
                        + " marker";
        markerWriter.write(
                id,
                new GithubMarkerWrite(
                        GithubMarkerKind.INDEX_REPAIR,
                        shape,
                        "🤖 gnomish: index repaired — observed " + shape,
                        null,
                        null,
                        markerWriter.instanceId(),
                        Instant.now()));
    }

    /** The label flip the observed boundary implies; no boundary at all rolls the claim back. */
    private void flip(GithubTaskId id, @org.jspecify.annotations.Nullable BoundaryKind boundary) {
        String target = boundary == null
                ? labels.ready()
                : switch (boundary) {
                    case ABORT, STALE_CLAIM_REMOVED -> labels.ready();
                    case PARK -> labels.needsHuman();
                    case FINISH -> labels.delivered();
                };
        labelOps.transition(id.owner(), id.repo(), id.issueNumber(), labels.working(), target);
    }
}
