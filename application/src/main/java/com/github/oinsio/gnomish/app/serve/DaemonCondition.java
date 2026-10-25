package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.logtext.LogText;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.Random;
import org.jspecify.annotations.Nullable;

/**
 * One daemon condition (design D19 of add-sandbox-base-image; design D9 of add-base-ref-resolution,
 * where it began as the remote outage gate): process-local, one per precondition of {@link
 * ConditionSubject#kind()}, held by {@link DaemonConditions}, which the feed consults before every
 * claim. A precondition the daemon lost is its condition, not a task's, so the feed stops spending
 * claims on it instead of releasing and re-claiming at feed speed. Deliberately NOT a background
 * thread: {@link #probeIfDue()} is driven forward by the feed's own poll cadence ({@link
 * FeedCycle#poll}).
 *
 * <p><b>What this class keeps.</b> Only the open/closed transition. What the condition is about and
 * how it learns the precondition is met again is its {@link ConditionSubject}'s; probe scheduling is
 * {@link RemoteOutageProbeSchedule}'s, the failure/probe/released-claim counts are {@link
 * RemoteOutageCounters}', every line an operator sees is {@link RemoteOutageReporter}'s, and
 * production construction is {@link RemoteOutageGates}'. {@link RemoteOutageWiring} carries the
 * construction-time choices as one parameter object.
 *
 * <p><b>Signal source.</b> The remote's slot-side signals — {@link #openOnFailure} and {@link
 * #onSuccessfulRefresh} — arrive from {@link RemoteOutageSignalingBaseRefGit}, the base-ref
 * decoration every serve slot reads through, and {@link #openOnFailure} also from {@link
 * RemoteOutageSignalingTaskBranchGit} (finding F88 of tighten-spec-contract), each at the instant
 * the underlying git read returns; no terminal slot result is interpreted as a remote signal, since
 * it would report a fact hours old.
 *
 * <p><b>The rule FR14's two events keep apart.</b> A probe closes the condition but never resets
 * the interval — only the first successful refresh after a close does ({@link
 * #onSuccessfulRefresh()}) — so a flapping remote (probe passes, refresh fails) reopens at a longer
 * interval, never at the idle floor. A close also calls {@code onTransition} for the snapshot's
 * immediate write and hands {@code onClosedOutage} the episode's summary for its ledger line,
 * exactly once per episode (NFR-O1, NFR-O3).
 *
 * <p><b>File size, accepted deviation.</b> Above {@code process-invariants.md}'s 200-line cap
 * after the subject, reporter, schedule and counter splits (task 7.5a of add-sandbox-base-image
 * moved the probe into {@link ConditionSubject}). What is left is one responsibility, the
 * transition with its three-phase probe, and dividing it again would leave two halves reaching
 * into each other's state, which that same rule names as the worse outcome.
 *
 * <p>Implements FR14, NFR-R3, NFR-O1, NFR-O3, UX6, D9 of add-base-ref-resolution; FR15, D19 of
 * add-sandbox-base-image.
 */
public final class DaemonCondition {

    private final ConditionSubject subject;
    private final InstantSource clock;
    private final RemoteOutageProbeSchedule schedule;
    private final RemoteOutageReporter reporter;
    private final DirtyNotifier onTransition;
    private final RemoteOutageLedgerSink onClosedOutage;

    private final RemoteOutageCounters counters = new RemoteOutageCounters();
    private volatile boolean open;

    /**
     * Whether a probe subprocess is running right now, outside this object's monitor — the latch
     * that replaces the monitor's atomicity for the one step not held under it (see {@link
     * #probeIfDue()}). Guarded by {@code this}, written only by the probe phases below.
     */
    private boolean probeInFlight;

    private volatile @Nullable Instant openedAt;
    private volatile @Nullable String lastError;
    private volatile @Nullable Instant lastSuccessAt;

    /**
     * Convenience overload for specs: the full constructor with {@link
     * RemoteOutageWiring#defaults(InstantSource)} on the condition's own {@code clock}.
     */
    DaemonCondition(ConditionSubject subject, InstantSource clock, Random random, Duration idleInterval, Duration cap) {
        this(subject, clock, random, idleInterval, cap, RemoteOutageWiring.defaults(clock));
    }

    /**
     * The full constructor: {@link RemoteOutageWiring} bundles the construction-time choices as one
     * parameter object per the project's parameter-count limit. Package-private — production reaches
     * it through {@link RemoteOutageGates}. Every parameter is required (never null; durations
     * positive).
     */
    DaemonCondition(
            ConditionSubject subject,
            InstantSource clock,
            Random random,
            Duration idleInterval,
            Duration cap,
            RemoteOutageWiring wiring) {
        this.subject = subject;
        this.clock = clock;
        this.schedule = new RemoteOutageProbeSchedule(clock, random, idleInterval, cap);
        this.reporter = new RemoteOutageReporter(
                wiring.target(),
                ConditionLog.forKind(subject.kind(), wiring.target()),
                wiring.suppressor(),
                wiring.sustainedOpenThreshold());
        this.onTransition = wiring.onTransition();
        this.onClosedOutage = wiring.onClosedOutage();
    }

    /** The precondition this condition holds the feed on. */
    public DaemonConditionKind kind() {
        return subject.kind();
    }

    /** Whether the condition currently blocks a claim attempt; cheap, read every feed cycle. */
    boolean isOpen() {
        return open;
    }

    /**
     * A read-only view of this condition's current state, for the snapshot's {@code remote} section.
     *
     * @return this condition's current {@link RemoteOutageHealth}; never null
     */
    public RemoteOutageHealth health() {
        return new RemoteOutageHealth(
                reporter.target(),
                open,
                openedAt,
                lastError,
                open ? schedule.nextProbeAt() : null,
                counters.consecutiveFailures(),
                lastSuccessAt);
    }

    /**
     * Records that {@code claimOrAbandon} abandoned a permit with no claim attempt because the condition
     * was open (FR14), for the outage's eventual ledger line. A stray call while closed is tolerated
     * rather than asserted against.
     */
    void claimReleasedWhileOpen() {
        counters.claimReleased();
    }

    /**
     * Opens the condition on a slot's {@code InfrastructureUnavailable} result (design D9). A no-op
     * while already open: a second slot's failure during the same outage does not restart the probe
     * schedule, log a second WARN, or reset the counters. {@code reason} is routed through {@link
     * LogText} since it may carry subprocess/git output.
     *
     * <p>{@code synchronized}: slots run on their own virtual threads and can discover the same dead
     * remote at the same instant (task 7.5's end-to-end scenario), so the check-then-set above must
     * be atomic — unsynchronized, two callers both observed {@code open == false} and both
     * transitioned, producing two WARNs for one outage. The same monitor serializes {@link
     * #onSuccessfulRefresh()} and {@link #probeIfDue()}'s guarded phases; only the probe subprocess
     * sits outside it.
     */
    synchronized void openOnFailure(String reason) {
        if (open) {
            return;
        }
        String scrubbed = LogText.forLog(reason);
        open = true;
        openedAt = clock.instant();
        lastError = scrubbed;
        counters.opened();
        schedule.openedFreshly();
        reporter.opened(scrubbed);
        onTransition.markDirty();
    }

    /**
     * The only path that resets the probe interval to the idle floor (FR14): the first successful
     * base refresh observed after a close. Also records the refresh as the remote's last successful
     * contact.
     *
     * <p>Ignored while the condition is open: the caller reports a slot's terminal result, and a slot
     * that fetched its base before the outage can finish during it — that refresh predates the
     * open, so it is neither "after the close" (it must not shrink the growing probe schedule, nor
     * spend the pending reset the genuine post-close refresh needs) nor evidence the remote is
     * back (recovery is confirmed by a probe, never by a slot).
     */
    synchronized void onSuccessfulRefresh() {
        if (open) {
            return;
        }
        lastSuccessAt = clock.instant();
        schedule.onSuccessfulRefresh();
    }

    /**
     * Runs one probe if the condition is open and its scheduled instant has passed; a no-op otherwise. A
     * success closes the condition without resetting the interval (the class javadoc's flapping rule).
     *
     * <p><b>Deliberately not {@code synchronized}</b> ({@code lock-scope.md}, after CERT LCK09-J).
     * The probe is a {@code git ls-remote} subprocess bounded by {@code factory.git-network-timeout}
     * — five minutes by default — and this object's monitor is the one every slot's {@link
     * #openOnFailure}/{@link #onSuccessfulRefresh} signal takes, so holding it across the subprocess
     * stalled exactly the threads whose signals are no-ops while the condition is open. Hence the three
     * phases: decide under the monitor, run the subprocess with nothing held, retake it to record
     * the answer. {@link #probeInFlight} keeps that atomic — a second driver finds the probe running
     * and leaves — and nothing is revalidated on the way back in, deliberately: while a probe is in
     * flight the condition is open and no path can change that (both slot signals return early while
     * open, and {@link #close()} is reached only from here).
     *
     * <p><b>A probe that throws is a failed probe.</b> A check that cannot answer at all (an image
     * inspection refused because the tag is gone, a git binary missing) proves nothing about the
     * precondition, so the condition stays open, counts the probe as failed, grows its schedule and
     * writes the failure's trace into the DEBUG streak — it never propagates. The caller is the feed
     * thread, which has no catch of its own: a propagated probe failure ended it, and with it the
     * daemon's duty to claim, with a green build (finding 1 of the 2026-10-25 audit of
     * add-sandbox-base-image). NFR-R2's crash arm is a slot's, with its abort accounting; a probe
     * has none, and the condition it probes is already open on a typed signal.
     *
     * @return the refusal of another precondition the probe met first ({@link
     *     ConditionProbe.Answer.RefusedElsewhere}), for the caller to open outside this monitor;
     *     empty otherwise
     */
    Optional<TakeResult.OperatorPreconditionUnmet> probeIfDue() {
        if (!beginProbe()) {
            return Optional.empty();
        }
        ConditionProbe.Answer answer;
        try {
            answer = subject.probe().probe();
        } catch (RuntimeException e) {
            recordUnanswered(e);
            return Optional.empty();
        }
        return applyProbeResult(answer);
    }

    /**
     * Phase one: claims the right to run this cycle's probe. True only when the condition is open, no
     * probe is already running, and the schedule says one is due.
     */
    private synchronized boolean beginProbe() {
        boolean due = open && !probeInFlight && schedule.isDue();
        if (due) {
            probeInFlight = true;
        }
        return due;
    }

    /**
     * Phase three, when the probe threw: a failed probe whose reason is the failure's own words,
     * with its trace on the DEBUG line; the latch is released, or this condition would never probe
     * again and could only close on a daemon restart.
     */
    private synchronized void recordUnanswered(RuntimeException cause) {
        probeInFlight = false;
        String reason = "probe did not answer: " + LogText.forLog(cause.toString());
        recordProbeFailure(reason);
        reporter.probeUnanswered(reason, cause);
        reportSustainedOpen(reason);
    }

    /**
     * Phase three: records the answer the probe came back with and releases the latch. A refusal of
     * another precondition leaves this one unjudged, so it counts as a failed probe here and is
     * handed back to be opened as its own.
     */
    private synchronized Optional<TakeResult.OperatorPreconditionUnmet> applyProbeResult(ConditionProbe.Answer answer) {
        probeInFlight = false;
        switch (answer) {
            case ConditionProbe.Answer.Met _ -> close();
            case ConditionProbe.Answer.Unmet unmet -> onProbeFailed(unmet.reason());
            case ConditionProbe.Answer.RefusedElsewhere elsewhere -> {
                onProbeFailed("not judged: precondition " + elsewhere.refusal().kind() + " refused first");
                return Optional.of(elsewhere.refusal());
            }
        }
        return Optional.empty();
    }

    private void onProbeFailed(String reason) {
        recordProbeFailure(reason);
        reporter.probeFailed(reason);
        reportSustainedOpen(reason);
    }

    private void recordProbeFailure(String reason) {
        counters.probeFailed();
        lastError = reason;
        schedule.probeFailed();
    }

    private void reportSustainedOpen(String reason) {
        Instant since = openedAt;
        if (since != null) {
            reporter.sustainedOpen(since, clock.instant(), reason);
        }
    }

    private void close() {
        Instant openedAtSnapshot = openedAt;
        Instant closedAt = clock.instant();
        int probeCount = counters.failedProbeCount();
        int released = counters.releasedClaims();
        String lastErrorSnapshot = lastErrorOrUnknown(lastError);
        open = false;
        lastSuccessAt = closedAt;
        counters.closed();
        openedAt = null;
        lastError = null;
        if (openedAtSnapshot != null) {
            reporter.closed(Duration.between(openedAtSnapshot, closedAt), probeCount);
            onClosedOutage.outageClosed(new RemoteOutageClosedOutage(
                    subject.kind(),
                    reporter.target(),
                    openedAtSnapshot,
                    closedAt,
                    probeCount,
                    released,
                    lastErrorSnapshot));
        }
        onTransition.markDirty();
    }

    // PIT documented exception: `lastError` has three writers — openOnFailure, onProbeFailed and
    // close(). openOnFailure is the only one that sets `openedAt`, and it sets `lastError` in the
    // same synchronized transition (never one without the other); onProbeFailed only ever runs
    // while the condition is open, so it replaces a non-null value with another non-null one; close()
    // clears both together. So by the time close() reads it here, `openedAt` being non-null already
    // guarantees `lastError` is non-null too. The "unknown" arm has no reachable input under the
    // class's own invariant; isolated to its own method per the project's convention for an
    // unkillable branch, so it does not hide as a false SURVIVED against close()'s real logic.
    @DoNotMutate
    private static String lastErrorOrUnknown(@Nullable String lastError) {
        return lastError == null ? "unknown" : lastError;
    }
}
