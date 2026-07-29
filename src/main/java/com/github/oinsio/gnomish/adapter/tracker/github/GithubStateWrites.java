package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.AbortRecord;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Locale;

/**
 * Implements the three label-changing {@code Tracker} write operations for
 * the GitHub adapter (design D13's boundary list, github-tracker spec):
 * {@code park} (working &rarr; needs-human, {@code report}-kind marker
 * carrying the {@link ParkReason} wire value), {@code finish} (working
 * &rarr; delivered, plain {@code report}-kind marker), and {@code
 * recordAbort} (working &rarr; ready, {@code abort}-kind marker). Each
 * method performs the point label transition via {@link GithubLabelOps}
 * plus one structural comment POST, copying the request-building shape
 * already used by {@code GithubClaimLease#postClaimComment} and {@link
 * GithubDecisions#acknowledgeDecision}.
 *
 * <p>{@code recordProgress} (fix-abort-progress-reset design D3) is the
 * exception to that pattern: it posts a {@code progress}-kind structural
 * marker only, with no label transition at all — it anchors abort-count
 * reconstruction without acting as a label-state boundary.
 *
 * <p>Judgment call (task 4.14): design D9's marker-kind vocabulary has no
 * dedicated {@code park}/{@code finish} kind. A park is a {@code
 * report}-kind marker carrying the optional {@code reason} field (task
 * 4.10's resolution, {@link GithubMarker#render(GithubMarkerKind, String,
 * Instant, String, String)}); a finish is a {@code report}-kind marker with
 * no reason, matching {@link GithubMarkerKind}'s own Javadoc ("a
 * finished-stage or final report") — there is no behavioral difference a
 * fresh instance would need to tell a park report from a finish report
 * apart from the label state itself, which each method already changes.
 *
 * <p>Implements FR14, FR18 of add-tracker-port.
 */
// Not a record: this is a behavior-bearing state-write service (a collaborator holding an HTTP
// client and label ops, not immutable data), kept as a plain final class.
@SuppressWarnings("ClassCanBeRecord")
public final class GithubStateWrites {

    private final GithubHttpClient httpClient;
    private final GithubLabelOps labelOps;
    private final String instanceId;
    private final String workingLabel;
    private final String needsHumanLabel;
    private final String deliveredLabel;
    private final String readyLabel;

    public GithubStateWrites(
            GithubHttpClient httpClient,
            GithubLabelOps labelOps,
            String instanceId,
            String workingLabel,
            String needsHumanLabel,
            String deliveredLabel,
            String readyLabel) {
        this.httpClient = httpClient;
        this.labelOps = labelOps;
        this.instanceId = instanceId;
        this.workingLabel = workingLabel;
        this.needsHumanLabel = needsHumanLabel;
        this.deliveredLabel = deliveredLabel;
        this.readyLabel = readyLabel;
    }

    /** Implements {@code Tracker.park} for GitHub (FR13, FR14 context, UX3). */
    public void park(TaskRef ref, ParkReason reason, String report) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        labelOps.transition(id.owner(), id.repo(), id.issueNumber(), workingLabel, needsHumanLabel);
        String body = GithubMarker.render(
                GithubMarkerKind.REPORT,
                instanceId,
                Instant.now(),
                report,
                reason.name().toLowerCase(Locale.ROOT));
        postComment(id, body);
    }

    /** Implements {@code Tracker.finish} for GitHub (FR18). */
    public void finish(TaskRef ref, String summary) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        labelOps.transition(id.owner(), id.repo(), id.issueNumber(), workingLabel, deliveredLabel);
        String body = GithubMarker.render(GithubMarkerKind.REPORT, instanceId, Instant.now(), summary);
        postComment(id, body);
    }

    /** Implements {@code Tracker.recordAbort} for GitHub (FR14, NFR-R3). */
    public void recordAbort(TaskRef ref, AbortRecord record) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        labelOps.transition(id.owner(), id.repo(), id.issueNumber(), workingLabel, readyLabel);
        String body = GithubMarker.render(
                GithubMarkerKind.ABORT, record.instance(), record.at(), "🤖 gnomish: aborted: " + record.cause());
        postComment(id, body);
    }

    /**
     * Implements {@code Tracker.recordProgress} for GitHub (FR1, FR4 of
     * fix-abort-progress-reset). The structural JSON rides in {@link
     * GithubMarker}'s hidden HTML comment and the human-readable line is a
     * terse one-liner, so the marker never adds visible noise to the tracker
     * thread (UX1 of fix-abort-progress-reset).
     */
    public void recordProgress(TaskRef ref) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        String body = GithubMarker.render(
                GithubMarkerKind.PROGRESS, instanceId, Instant.now(), "🤖 gnomish: progress recorded");
        postComment(id, body);
    }

    private void postComment(GithubTaskId id, String body) {
        String path = "/repos/%s/%s/issues/%d/comments".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpRequest.Builder request = httpClient
                .newRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GithubCommentBody.toJson(body)));

        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw new GithubStateWriteException("Failed to post structural comment on %s/%s#%d: HTTP %d"
                    .formatted(id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
    }
}
