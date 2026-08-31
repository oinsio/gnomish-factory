package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.util.Map;
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
 * <p><b>The second, weaker state: unconfirmed (FR13 of harden-task-branch-contract).</b> A claim
 * whose beats have been failing past the lost-detection threshold is not known to be lost — the
 * tracker may simply be unreachable — but it is no longer known to be held either. That claim is
 * marked <i>unconfirmed</i> here, and the run freezes its writes at the next boundary until it
 * re-verifies the claim: no round commit, no push, no tracker write. Unlike a lost claim this state
 * is reversible in both directions — a beat that lands clears it, and a boundary re-verification
 * that finds the claim still ours clears it too — so it is held in its own set rather than in the
 * latching map above. The two never contradict each other: a claim proved gone is lost, and the
 * lost path already ends the run at the same boundary the freeze would have held it at.
 *
 * <p>Freezing before the reaper can reassign is what makes the fence useful: the lost-detection
 * threshold that sets this state is strictly earlier than the reaper's reassignment threshold
 * (claim-heartbeat "Lost-detection strictly precedes reassignment"), so a holder has already
 * stopped writing by the time any other instance could be handed the task.
 *
 * <p>Implements FR8 of add-claim-heartbeat. Implements FR13 of harden-task-branch-contract.
 */
public final class ClaimLossFlag implements ClaimLostSink {

    /**
     * The message {@code RevocationCheckingAttemptPersistence} has always folded into the
     * revocation it throws for a heartbeat-detected loss; kept as the default reason so {@link
     * #claimLost(TaskRef)} (no explicit reason) reads exactly as before (add-claim-heartbeat, FR8).
     */
    static final String DEFAULT_REASON = "claim marker gone (heartbeat reported loss)";

    private final Map<TaskRef, String> lost = new ConcurrentHashMap<>();
    private final Set<TaskRef> unconfirmed = ConcurrentHashMap.newKeySet();

    /**
     * Records that {@code ref}'s claim is lost, called by the beat thread when a beat
     * answered {@code ClaimGone}. Idempotent: a second call for an already-lost claim
     * leaves the flag set. Equivalent to {@link #claimLost(TaskRef, String)} with {@link
     * #DEFAULT_REASON}.
     *
     * <p>Implements FR8 of add-claim-heartbeat.
     *
     * @param ref the task whose claim was lost; never null
     */
    @Override
    public void claimLost(TaskRef ref) {
        claimLost(ref, DEFAULT_REASON);
    }

    /**
     * Records that {@code ref}'s claim is lost for a caller-supplied {@code reason} — used by the
     * {@code serve} SIGTERM shutdown sequence (FR11, D9 of add-factory-serve) to flag an
     * in-flight slot's claim as gracefully stopped rather than lost, so the round-boundary
     * reaction posts an accurate note instead of the heartbeat's generic wording. Latches like
     * {@link #claimLost(TaskRef)}: only the FIRST reason recorded for a ref sticks, since a claim
     * once flagged never un-flags or changes cause within a run.
     *
     * <p>Implements FR8 of add-claim-heartbeat. Implements FR11, D9 of add-factory-serve.
     *
     * @param ref the task whose claim was lost; never null
     * @param reason the human-readable cause folded into the round-boundary revocation message;
     *     never null
     */
    public void claimLost(TaskRef ref, String reason) {
        lost.putIfAbsent(ref, reason);
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
        return lost.containsKey(ref);
    }

    /**
     * The reason recorded for {@code ref}'s loss — whatever {@link #claimLost(TaskRef, String)}
     * (or the default from {@link #claimLost(TaskRef)}) first recorded — for {@code
     * RevocationCheckingAttemptPersistence} to fold into the revocation it throws at the round
     * boundary. Unflagged refs answer {@link #DEFAULT_REASON}, which never actually surfaces since
     * callers only consult this after {@link #isLost(TaskRef)} has already answered {@code true}.
     *
     * <p>Implements FR8 of add-claim-heartbeat. Implements FR11, D9 of add-factory-serve.
     *
     * @param ref the task to look up; never null
     * @return the recorded reason, or {@link #DEFAULT_REASON} if {@code ref} is not flagged lost
     */
    public String reason(TaskRef ref) {
        return lost.getOrDefault(ref, DEFAULT_REASON);
    }

    /**
     * Records that {@code ref}'s claim can no longer be confirmed, freezing the run's writes at its
     * next boundary until it re-verifies (FR13). Idempotent, and reversible by {@link
     * #claimConfirmed(TaskRef)} or {@link #confirmedByReverification(TaskRef)}.
     *
     * <p>Implements FR13 of harden-task-branch-contract.
     *
     * @param ref the task whose claim is unconfirmed; never null
     */
    @Override
    public void claimUnconfirmed(TaskRef ref) {
        unconfirmed.add(ref);
    }

    /**
     * Records that a beat confirmed {@code ref}'s claim live again, lifting the freeze (FR13).
     *
     * <p>Implements FR13 of harden-task-branch-contract.
     *
     * @param ref the task whose claim is confirmed; never null
     */
    @Override
    public void claimConfirmed(TaskRef ref) {
        unconfirmed.remove(ref);
    }

    /**
     * Lifts the freeze after the run's own boundary re-verification found the claim still ours —
     * the second way out of the frozen state, and the one that matters when the beats are still
     * failing but a single conditional read got through (FR13). Named apart from {@link
     * #claimConfirmed(TaskRef)} so the two callers stay visible: the beat thread confirms through
     * the sink, the run confirms through this.
     *
     * <p>Implements FR13 of harden-task-branch-contract.
     *
     * @param ref the task whose claim was re-verified; never null
     */
    public void confirmedByReverification(TaskRef ref) {
        unconfirmed.remove(ref);
    }

    /**
     * Reports whether {@code ref}'s claim is currently unconfirmed, for the round boundary to
     * consult before it writes anything (FR13).
     *
     * <p>Implements FR13 of harden-task-branch-contract.
     *
     * @param ref the task to test; never null
     * @return {@code true} while the holder cannot confirm the claim is live
     */
    public boolean isUnconfirmed(TaskRef ref) {
        return unconfirmed.contains(ref);
    }
}
