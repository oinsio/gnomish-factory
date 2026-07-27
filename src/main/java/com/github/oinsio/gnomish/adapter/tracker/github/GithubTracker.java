package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord;
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import java.util.List;

/**
 * The GitHub {@link Tracker} composition root (design D15, task 4.16): pure
 * delegation to the concern-split pieces built across tasks 4.4-4.15 — {@link
 * GithubFeedQuery} (feed), {@link GithubTaskFetcher} (fact set), {@link
 * GithubClaimLease} (claim), {@link GithubStateWrites} (park/finish/
 * recordAbort), {@link GithubCorrespondence} (release/postNote), and {@link
 * GithubDecisions} (collectDecisions/acknowledgeDecision). This class holds
 * no HTTP or business logic of its own: every method is a one-line forward
 * to the collaborator that already implements it, wired once at
 * construction with the shared {@link GithubHttpClient}/label names/{@code
 * instanceId} every collaborator needs.
 *
 * <p>Implements FR1, FR4, NFR-R1 of add-tracker-port.
 *
 * <p>Callers typically build the individual collaborators once (each needing
 * only a subset of these) and pass them here; this record takes the
 * collaborators directly rather than re-deriving them, so construction order
 * and shared instances (e.g. one {@link GithubConditionalRequestCache} across
 * polls, NFR-P1) stay the caller's responsibility.
 *
 * @param feedQuery implements {@code listReady}
 * @param taskFetcher implements {@code fetchTask}
 * @param claimLease implements {@code claim}
 * @param stateWrites implements {@code park}/{@code finish}/{@code recordAbort}
 * @param correspondence implements {@code release}/{@code postNote}
 * @param decisions implements {@code collectDecisions}/{@code acknowledgeDecision}
 */
public record GithubTracker(
        GithubFeedQuery feedQuery,
        GithubTaskFetcher taskFetcher,
        GithubClaimLease claimLease,
        GithubStateWrites stateWrites,
        GithubCorrespondence correspondence,
        GithubDecisions decisions)
        implements Tracker {

    @Override
    public List<ReadyTask> listReady(int limit) {
        return feedQuery.listReady(limit);
    }

    @Override
    public TrackerTask fetchTask(TaskRef ref) {
        return taskFetcher.fetchTask(ref);
    }

    @Override
    public List<HumanReply> collectDecisions(TaskRef ref) {
        return decisions.collectDecisions(ref);
    }

    @Override
    public ClaimResult claim(TaskRef ref, String instanceId) {
        return claimLease.claim(ref, instanceId);
    }

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate — this
    // delegation call is a genuine equivalent mutant: GithubCorrespondence#release is an explicit,
    // documented no-op for the GitHub adapter (design D2, FR15 "state untouched" — see that
    // class's Javadoc for the full reasoning), so removing this call changes no observable
    // behavior whatsoever, unlike every other delegation in this class. GithubTrackerSpec's
    // "release delegates" scenario proves the no-op contract holds (no HTTP call at all).
    @DoNotMutate
    @Override
    public void release(TaskRef ref) {
        correspondence.release(ref);
    }

    @Override
    public void park(TaskRef ref, ParkReason reason, String report) {
        stateWrites.park(ref, reason, report);
    }

    @Override
    public void finish(TaskRef ref, String summary) {
        stateWrites.finish(ref, summary);
    }

    @Override
    public void recordAbort(TaskRef ref, AbortRecord record) {
        stateWrites.recordAbort(ref, record);
    }

    @Override
    public void acknowledgeDecision(TaskRef ref, String decisionText) {
        decisions.acknowledgeDecision(ref, decisionText);
    }

    @Override
    public void postNote(TaskRef ref, String text) {
        correspondence.postNote(ref, text);
    }
}
