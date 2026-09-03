package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts;
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements {@code Tracker.removeStaleClaim} for the GitHub adapter (design D5,
 * D12, github-tracker spec "Stale-claim removal physics"): a version-guarded
 * reap that returns a dead holder's task to circulation without ever claiming it
 * for the caller.
 *
 * <p>The pre-action re-check reads the thread with a DIRECT list-comments GET —
 * never the conditional-request cache: the caller's {@code observedVersion} came
 * from a possibly-cached {@code listOpen}, so the current truth must be re-read
 * fresh here. The resolved claim's {@code (comment id, updated_at)} is compared
 * against {@code observedVersion}; any difference — the claim was beaten, already
 * removed, or replaced — is a safe no-op returning {@link
 * RemoveStaleClaimResult.Mismatch} (the live version, or {@code null} when the
 * claim is already gone), which is what makes concurrent removals converge
 * (NFR-R2). A {@code 404} on the re-read itself (the issue is gone) is the same
 * {@code Mismatch(null)} no-op — the claim vanished with its task, never an
 * infrastructure failure.
 *
 * <p>On a match it acts in order: (a) POST the {@code stale-claim-removed}
 * boundary marker FIRST — it names the dead holder, the removed comment id, the
 * observed version, and the time in its human-readable line, and anchors the next
 * lease round's verify-read like any other boundary — so the boundary exists even
 * if a later step fails; (b) DELETE the dead claim comment (all instances share
 * one token, so deletion is possible), tolerating a {@code 404} from a racing
 * remover that already deleted it; (c) flip the working label back to ready via
 * {@link GithubLabelOps#transition} (idempotent-in-effect: {@code removeLabel}
 * treats 404 as success, re-adding ready is harmless). Both a network failure and a
 * persistent 5xx are retried inside {@link GithubHttpClient} (NFR-R2), but surface
 * differently once retries are exhausted: a network failure propagates as an
 * infrastructure {@link com.github.oinsio.gnomish.adapter.github.GithubHttpException},
 * whereas an exhausted 5xx is returned as a
 * non-2xx response and throws a {@link GithubStaleClaimException} from the failing step.
 *
 * <p>The boundary marker is written through the shared {@link GithubMarkerWriter} (FR11), scoped
 * by the epoch of the claim being removed rather than by a tenure this reaper does not hold: a
 * re-driven removal of the same dead claim updates its own marker (UX3), while a later reap of a
 * later tenure posts its own — which the lease anchor depends on, since it takes the latest
 * boundary by position and updating a comment in place does not move it.
 *
 * <p>A removal is a destructive action taken against another instance's tenure, so the one that
 * happens is an INFO anchor naming the holder whose claim was retired; the far more common
 * converge-abort — the pre-action re-check finding the claim already beaten, removed or replaced —
 * is DEBUG, since two reapers converging is the mechanism working, not a degradation (FR5, FR12 of
 * harden-logging-observability).
 *
 * <p>Implements FR4, FR5 of add-claim-heartbeat; FR11 of harden-task-branch-contract; FR5 of
 * harden-logging-observability.
 */
// Not a record: this is a behavior-bearing removal service (a collaborator holding an HTTP client,
// label ops and this instance's id, not immutable data), kept as a plain final class for parity
// with its documented siblings GithubClaimLease / GithubStateWrites / GithubHeartbeat.
@SuppressWarnings("ClassCanBeRecord")
public final class GithubStaleClaimRemoval {

    private static final Logger log = LoggerFactory.getLogger(GithubStaleClaimRemoval.class);

    private final GithubHttpClient httpClient;
    private final GithubLabelOps labelOps;
    private final GithubMarkerWriter markerWriter;
    private final String workingLabel;
    private final String readyLabel;

    public GithubStaleClaimRemoval(
            GithubHttpClient httpClient,
            GithubLabelOps labelOps,
            GithubMarkerWriter markerWriter,
            String workingLabel,
            String readyLabel) {
        this.httpClient = httpClient;
        this.labelOps = labelOps;
        this.markerWriter = markerWriter;
        this.workingLabel = workingLabel;
        this.readyLabel = readyLabel;
    }

    /** Implements {@code Tracker.removeStaleClaim} for GitHub (FR4, FR5, FR19). */
    public RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimFacts observedClaim) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        Optional<List<GithubClaimComment.Candidate>> thread = reReadThread(id);
        if (thread.isEmpty()) {
            // The issue itself is gone, so the claim is gone with it: a converging no-op.
            return convergeAbort(ref, "the issue is gone", null);
        }
        ClaimFacts current = GithubTrackerFacts.claim(thread.get());
        if (!current.equals(observedClaim)) {
            return convergeAbort(ref, "the claim moved since it was observed", current.liveVersion());
        }
        return switch (current) {
            case ClaimFacts.Live live -> removeLiveClaim(ref, id, thread.get(), live);
            // A dead footprint has no comment left to delete: the boundary marker and the label
            // flip still run, which is exactly what retires the footprint and returns the task.
            case ClaimFacts.Dead dead -> retire(ref, id, dead.lastKnownHolder(), null, null);
            // No footprint at all is the one input with nothing to retire; reporting the absent
            // facts converges rather than posting a boundary for a tenure that left no trace.
            case ClaimFacts.None ignored -> convergeAbort(ref, "the tenure left no footprint", null);
        };
    }

    /** The live-claim path: retire the footprint naming its holder, then delete its own comment. */
    private RemoveStaleClaimResult removeLiveClaim(
            TaskRef ref, GithubTaskId id, List<GithubClaimComment.Candidate> thread, ClaimFacts.Live live) {
        GithubClaimComment.Candidate claim = GithubClaimComment.resolve(thread).orElseThrow();
        return retire(ref, id, live.holder(), claim.id(), live.version());
    }

    /**
     * The no-op every convergence path returns, and the one place it is recorded. DEBUG: a reap
     * that finds nothing to reap is two instances agreeing, which the operator has nothing to do
     * about — but a reaper that never removes anything is exactly what someone diagnosing "why is
     * this claim still here" needs to read.
     */
    private static RemoveStaleClaimResult convergeAbort(TaskRef ref, String why, @Nullable ClaimVersion liveVersion) {
        log.debug("stale-claim removal for task {} converged without acting: {}", ref.id(), why);
        return new RemoveStaleClaimResult.Mismatch(liveVersion);
    }

    /**
     * The removal physics, shared by both footprint shapes: the boundary marker FIRST — it names
     * the dead (or last-known) holder and anchors the next lease round's verify-read — then the
     * dead claim comment's deletion when there is one, then the ready flip. Constructive before
     * destructive, so a kill anywhere leaves a shape the sweep still enumerates.
     */
    private RemoveStaleClaimResult retire(
            TaskRef ref,
            GithubTaskId id,
            String deadHolder,
            @Nullable Long commentId,
            @Nullable ClaimVersion observedVersion) {
        postRemovalMarker(id, deadHolder, commentId, observedVersion);
        if (commentId != null) {
            deleteClaimComment(id, commentId);
        }
        labelOps.transition(id.owner(), id.repo(), id.issueNumber(), workingLabel, readyLabel);
        log.info(
                "removed stale claim of instance {} on task {}; the task is back in circulation", deadHolder, ref.id());
        return new RemoveStaleClaimResult.Removed();
    }

    /**
     * Freshly re-reads and resolves the live claim comment, or empty when there is nothing to
     * remove — folding into the {@link RemoveStaleClaimResult.Mismatch}{@code (null)} no-op above.
     * Empty covers two already-gone shapes: a thread whose claim comment is already deleted (a
     * racing remover converged), and a {@code 404} on the re-read itself — the issue is gone, so
     * the claim is gone too. Both converge without error (NFR-R2). Only a NON-404 non-2xx (403, an
     * exhausted 5xx) is an infrastructure failure worth throwing.
     */
    private Optional<List<GithubClaimComment.Candidate>> reReadThread(GithubTaskId id) {
        String path = "/repos/%s/%s/issues/%d/comments?per_page=100".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpResponse<String> response =
                httpClient.send(httpClient.newRequest(path).GET());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new GithubStaleClaimException(
                    "Failed to re-read comments while removing a stale claim on %s/%s#%d: HTTP %d"
                            .formatted(id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
        return Optional.of(GithubClaimComment.parse(response.body()));
    }

    private void postRemovalMarker(
            GithubTaskId id, String deadHolder, @Nullable Long commentId, @Nullable ClaimVersion observedVersion) {
        String human = observedVersion == null
                ? "🤖 gnomish: stale claim removed — dead holder %s, no live claim comment left".formatted(deadHolder)
                : "🤖 gnomish: stale claim removed — dead holder %s, removed comment %d, observed version %s@%s"
                        .formatted(deadHolder, commentId, observedVersion.markerId(), observedVersion.updatedAt());
        // Scoped by the epoch of the claim being removed, so a re-driven removal of the same tenure
        // updates its own marker while a later reap posts its own (FR11, UX3). A dead footprint has
        // no epoch to scope by — the removal is then scoped by the holder it retires.
        String scope = observedVersion == null
                ? deadHolder
                : Long.toString(observedVersion.epoch().token());
        markerWriter.write(
                id,
                new GithubMarkerWrite(
                        GithubMarkerKind.STALE_CLAIM_REMOVED,
                        scope,
                        human,
                        null,
                        null,
                        markerWriter.instanceId(),
                        Instant.now()));
    }

    private void deleteClaimComment(GithubTaskId id, long commentId) {
        String path = "/repos/%s/%s/issues/comments/%d".formatted(id.owner(), id.repo(), commentId);
        HttpResponse<String> response =
                httpClient.send(httpClient.newRequest(path).DELETE());
        // A 404 means a racing remover already deleted this comment — a harmless
        // convergence, not a failure (spec "Racing removals converge").
        if (response.statusCode() == 404 || response.statusCode() / 100 == 2) {
            return;
        }
        throw new GithubStaleClaimException("Failed to delete dead claim comment %d on %s/%s: HTTP %d"
                .formatted(commentId, id.owner(), id.repo(), response.statusCode()));
    }
}
