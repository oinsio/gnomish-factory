package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;

/**
 * Implements the three label-changing {@code Tracker} write operations for
 * the GitHub adapter (design D13's boundary list, github-tracker spec):
 * {@code park} (working &rarr; needs-human, {@code park}-kind marker
 * carrying the {@link ParkReason} wire value), {@code finish} (working
 * &rarr; delivered, {@code finish}-kind marker), and {@code
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
 * <p>{@code park} and {@code finish} write dedicated {@link
 * GithubMarkerKind#PARK} and {@link GithubMarkerKind#FINISH} markers rather
 * than sharing a single dual-use kind, so the distinction is structural, not
 * inferred from whether a {@code reason} field happens to be present (design
 * D1 of enforce-finish-terminality).
 *
 * <p>{@code declineFinished} (enforce-finish-terminality FR4, NFR-R1, design
 * D3/D5) restores the ready&rarr;delivered terminal transition and posts a
 * {@code note}-kind marker explaining the decline, only after the transition
 * succeeds, and is a silent no-op when the issue is already terminal.
 *
 * <p>Every marker here is written through {@link GithubMarkerWriter} — one renderer, find-then-
 * upsert, stamped with the tenure's claim epoch (FR11, FR13) — so a crash-retry of any of these
 * writes updates its own comment instead of appending a duplicate report (UX3).
 *
 * <p>Write order is contract (FR12 of harden-task-branch-contract): for {@code park}, {@code
 * finish} and {@code recordAbort} the structural marker posts BEFORE the label flip that indexes
 * it — markers are the truth, labels the index — so a kill between the two freezes a lagging index
 * the sweep repairs, never a recorded fact lost with an unflipped label. {@code declineFinished}
 * keeps the opposite order on purpose: its NOTE marker carries no derivation weight, so posting it
 * behind a failed transition would be pure noise (design D5 of enforce-finish-terminality).
 *
 * <p>Implements FR14, FR18 of add-tracker-port, FR1, FR2, FR4, NFR-R1, UX2 of
 * enforce-finish-terminality; FR12 of harden-task-branch-contract.
 */
// Not a record: this is a behavior-bearing state-write service (a collaborator holding an HTTP
// client and label ops, not immutable data), kept as a plain final class.
@SuppressWarnings("ClassCanBeRecord")
public final class GithubStateWrites {

    private final GithubHttpClient httpClient;
    private final GithubLabelOps labelOps;
    private final GithubMarkerWriter markerWriter;
    private final String workingLabel;
    private final String needsHumanLabel;
    private final String deliveredLabel;
    private final String readyLabel;

    public GithubStateWrites(
            GithubHttpClient httpClient,
            GithubLabelOps labelOps,
            GithubMarkerWriter markerWriter,
            String workingLabel,
            String needsHumanLabel,
            String deliveredLabel,
            String readyLabel) {
        this.httpClient = httpClient;
        this.labelOps = labelOps;
        this.markerWriter = markerWriter;
        this.workingLabel = workingLabel;
        this.needsHumanLabel = needsHumanLabel;
        this.deliveredLabel = deliveredLabel;
        this.readyLabel = readyLabel;
    }

    /**
     * Implements {@code Tracker.park} for GitHub (FR13, FR14 context, UX3). The marker is the truth
     * and the label is its index (FR12 of harden-task-branch-contract), so the marker posts first: a
     * kill between the two freezes an issue whose park report is on the thread while its labels
     * still say working — a lagging index the sweep completes — rather than a needs-human issue
     * whose report was lost with the instance that never posted it.
     */
    public void park(TaskRef ref, ParkReason reason, String report) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        markerWriter.write(id, GithubMarkerKind.PARK, report, reason.name().toLowerCase(Locale.ROOT));
        labelOps.transition(id.owner(), id.repo(), id.issueNumber(), workingLabel, needsHumanLabel);
    }

    /**
     * Implements {@code Tracker.finish} for GitHub (FR18). Marker before the delivered flip (FR12 of
     * harden-task-branch-contract): a kill between the two leaves the finished fact derivable from
     * the thread, so the sweep completes the flip and the delivered work is never re-executed —
     * where flipping first could mark a task delivered with no record of what was delivered.
     */
    public void finish(TaskRef ref, String summary) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        markerWriter.write(id, GithubMarkerKind.FINISH, summary, null);
        labelOps.transition(id.owner(), id.repo(), id.issueNumber(), workingLabel, deliveredLabel);
    }

    /**
     * Implements {@code Tracker.recordAbort} for GitHub (FR14, NFR-R3). Marker before the ready flip
     * (FR12 of harden-task-branch-contract). The trade is deliberate: a kill between the two counts
     * an abort whose flip never happened — an over-count, which pushes the task toward parking for a
     * human. Flipping first would trade the other way and lose an abort from the count, letting a
     * task loop past its fuse.
     */
    public void recordAbort(TaskRef ref, AbortRecord record) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        // Scoped by the tenure being aborted, and authored by the instance the record names: at
        // most one abort ends a tenure, so one abort comment per tenure keeps the count honest
        // while a crash-retry of this same abort updates it in place.
        ClaimEpoch tenure = markerWriter.tenureOf(id).orElse(null);
        String scope = tenure == null ? Long.toString(record.at().toEpochMilli()) : Long.toString(tenure.token());
        markerWriter.write(
                id,
                new GithubMarkerWrite(
                        GithubMarkerKind.ABORT,
                        scope,
                        "🤖 gnomish: aborted: " + record.cause(),
                        // The marker's reason field carries the recovery category, so the fold reads
                        // the two shares of the unified accounting back off the thread (FR14 of
                        // harden-task-branch-contract); a pre-categorization marker has none and
                        // reads as the crash category it meant.
                        record.category().wireValue(),
                        tenure,
                        record.instance(),
                        record.at()));
        labelOps.transition(id.owner(), id.repo(), id.issueNumber(), workingLabel, readyLabel);
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
        markerWriter.write(id, GithubMarkerKind.PROGRESS, "🤖 gnomish: progress recorded", null);
    }

    /**
     * Implements {@code Tracker.declineFinished} for GitHub (FR4, NFR-R1,
     * UX2, design D3/D5). Restores the terminal status (ready &rarr; delivered)
     * and, only once that transition succeeds, posts a {@link
     * GithubMarkerKind#NOTE}-kind marker carrying {@code message} — NOTE
     * rather than PARK/FINISH so this out-of-band explanation carries no
     * derivation weight (design D3). Ordering matches design D5: a failure
     * of the label transition propagates without ever reaching the comment
     * POST, so a crash never leaves a dangling half-decline that also
     * posted noise. If the issue already carries {@code deliveredLabel} the
     * task is already terminal and this method does nothing at all — no
     * label change, no comment (NFR-R1's state-level idempotency).
     */
    public void declineFinished(TaskRef ref, String message) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        if (isAlreadyTerminal(id)) {
            return;
        }
        labelOps.transition(id.owner(), id.repo(), id.issueNumber(), readyLabel, deliveredLabel);
        markerWriter.write(id, GithubMarkerKind.NOTE, message, null);
    }

    private boolean isAlreadyTerminal(GithubTaskId id) {
        String path = "/repos/%s/%s/issues/%d".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpRequest.Builder request = httpClient.newRequest(path).GET();
        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw new GithubStateWriteException("Failed to fetch issue %s/%s#%d: HTTP %d"
                    .formatted(id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
        GithubIssueDetail detail = GithubIssueDetailParser.parse(response.body());
        return detail.labelNames().contains(deliveredLabel);
    }
}
