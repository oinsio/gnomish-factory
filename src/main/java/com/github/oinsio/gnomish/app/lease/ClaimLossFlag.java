package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cross-thread hand-off for a lost claim (design D7, FR8): the {@link ClaimLostSink}
 * implementation the beat thread writes and the engine thread reads. When a beat answers
 * {@link com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult.ClaimGone} the
 * heartbeat calls {@link #claimLost(TaskRef)} on THIS instance, which records the ref; the
 * take run polls {@link #isLost(TaskRef)} at each round boundary — the two touch the flag
 * from different threads, so its state is held in a thread-safe set (a beat may set it at
 * any instant while the engine thread reads it).
 *
 * <p>Keyed by {@link TaskRef} so the general case — an instance holding several claims —
 * is modelled cleanly, even though a single {@code take} run holds exactly one claim; a
 * lost claim for one task never marks another. Recording is idempotent (a claim is either
 * lost or not) and one-way: the flag latches, because a claim once gone does not come back
 * within a run.
 *
 * <p><b>How task 6.1/6.3 wires the reaction.</b> A set flag means the claim is lost, and
 * the run reacts at its NEAREST round boundary exactly as it reacts to a revocation (design
 * D7 — "comment gone → claim lost, react at the nearest boundary exactly like a
 * revocation"). Concretely, the take run consults {@link #isLost(TaskRef)} at each round
 * boundary — alongside or as a sibling of the existing {@code
 * RevocationCheckingAttemptPersistence} check that already runs there — and a set flag
 * triggers the SAME handling as {@code RevocationDetectedException}: hand off to {@code
 * RevocationHandler}, which salvages the interrupted round, best-effort pushes the branch
 * (the git non-fast-forward fence arbitrates), posts a "work stopped" note, and {@code
 * release}s the claim. It NEVER calls {@code park}, {@code finish}, or {@code recordAbort} —
 * no tracker state is written for the task that is no longer ours (FR8): the claim was lost
 * because a human or another instance moved the task, and the run must get out of the way,
 * not fight that move. This core component provides the flag and the sink only; task 6.1/6.3
 * wires the boundary consult and the {@code RevocationHandler} hand-off in the take command,
 * leaving {@code RevocationCheckingAttemptPersistence} and {@code TakeCommand} untouched here.
 *
 * <p>Implements FR8 of add-claim-heartbeat.
 */
public final class ClaimLossFlag implements ClaimLostSink {

    private final Set<TaskRef> lost = ConcurrentHashMap.newKeySet();

    /**
     * Records that {@code ref}'s claim is lost, called by the beat thread when a beat
     * answered {@code ClaimGone}. Idempotent: a second call for an already-lost claim
     * leaves the flag set.
     *
     * <p>Implements FR8 of add-claim-heartbeat.
     *
     * @param ref the task whose claim was lost; never null
     */
    @Override
    public void claimLost(TaskRef ref) {
        lost.add(ref);
    }

    /**
     * Reports whether {@code ref}'s claim has been flagged lost, for the take run to poll at
     * each round boundary. A {@code true} answer means the run must stop at that boundary and
     * take the revocation-identical reaction (see the class doc); {@code false} means the
     * claim is still believed held and the run proceeds.
     *
     * <p>Implements FR8 of add-claim-heartbeat.
     *
     * @param ref the task to test; never null
     * @return {@code true} if a beat has flagged this claim lost, {@code false} otherwise
     */
    public boolean isLost(TaskRef ref) {
        return lost.contains(ref);
    }
}
