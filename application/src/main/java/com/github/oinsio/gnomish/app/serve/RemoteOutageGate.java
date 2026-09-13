package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.logtext.LogText;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The remote outage gate (design D9, FR14, NFR-R3 of add-base-ref-resolution): one owner class,
 * process-local and per remote target, that the feed consults before every claim and a slot opens
 * on its own {@code InfrastructureUnavailable} result — a dead remote is the daemon's condition,
 * not the task's, so the feed stops spending claims on it instead of releasing and re-claiming at
 * feed speed. Deliberately NOT a background thread: {@link #probeIfDue()} is driven forward by the
 * feed's own poll cadence ({@link FeedCycle#poll}).
 *
 * <p><b>What this class keeps.</b> Only the open/closed transition. Probe scheduling is {@link
 * RemoteOutageProbeSchedule}'s, the failure/probe/released-claim counts are {@link
 * RemoteOutageCounters}', every line an operator sees is {@link RemoteOutageReporter}'s, and
 * production construction is {@link RemoteOutageGates}'. Collaborators are driven through their own
 * small interfaces, never by reaching into their fields; {@link RemoteOutageWiring} carries the
 * construction-time choices as one parameter object.
 *
 * <p><b>Signal source.</b> Both slot-side signals — {@link #openOnFailure} and {@link
 * #onSuccessfulRefresh} — arrive from {@link RemoteOutageSignalingBaseRefGit}, the base-ref
 * decoration every serve slot reads through, at the instant the underlying git read returns; no
 * terminal slot result is interpreted as a signal, since it would report a fact hours old.
 *
 * <p><b>The rule FR14's two events keep apart.</b> A probe closes the gate but never resets the
 * interval — only the first successful refresh after a close does ({@link #onSuccessfulRefresh()})
 * — so a flapping remote (probe passes, refresh fails) reopens at a longer interval, never at the
 * idle floor. A close also calls {@code onTransition} for the snapshot's immediate write and hands
 * {@code onClosedOutage} the outage's summary for the {@code remoteOutage} ledger line, exactly
 * once per outage (NFR-O1, NFR-O3).
 *
 * <p><b>File size, accepted deviation.</b> Still above {@code process-invariants.md}'s 200-line
 * cap after the reporter and factory splits. What is left is one responsibility, and dividing the
 * transition again would leave two halves reaching into each other's state — which that same rule
 * names as the worse outcome. Revisit when a second remote target makes this genuinely two things.
 *
 * <p>Implements FR14, NFR-R3, NFR-O1, NFR-O3, UX6, D9 of add-base-ref-resolution.
 */
public final class RemoteOutageGate {

    private final BaseRefGit baseRefGit;
    private final Path cloneDir;
    private final Clock clock;
    private final RemoteOutageProbeSchedule schedule;
    private final RemoteOutageReporter reporter;
    private final Runnable onTransition;
    private final Consumer<RemoteOutageClosedOutage> onClosedOutage;

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

    /** Convenience overload for specs: the full constructor with {@link RemoteOutageWiring#defaults()}. */
    RemoteOutageGate(
            BaseRefGit baseRefGit, Path cloneDir, Clock clock, Random random, Duration idleInterval, Duration cap) {
        this(baseRefGit, cloneDir, clock, random, idleInterval, cap, RemoteOutageWiring.defaults());
    }

    /**
     * The full constructor: {@link RemoteOutageWiring} bundles the construction-time choices as one
     * parameter object per the project's parameter-count limit. Package-private — production reaches
     * it through {@link RemoteOutageGates}. Every parameter is required (never null; durations
     * positive).
     */
    RemoteOutageGate(
            BaseRefGit baseRefGit,
            Path cloneDir,
            Clock clock,
            Random random,
            Duration idleInterval,
            Duration cap,
            RemoteOutageWiring wiring) {
        this.baseRefGit = baseRefGit;
        this.cloneDir = cloneDir;
        this.clock = clock;
        this.schedule = new RemoteOutageProbeSchedule(clock, random, idleInterval, cap);
        this.reporter = new RemoteOutageReporter(wiring.target(), wiring.suppressor(), wiring.sustainedOpenThreshold());
        this.onTransition = wiring.onTransition();
        this.onClosedOutage = wiring.onClosedOutage();
    }

    /** Whether the gate currently blocks a claim attempt; cheap, read every feed cycle. */
    boolean isOpen() {
        return open;
    }

    /**
     * A read-only view of this gate's current state, for the snapshot's {@code remote} section.
     *
     * @return this gate's current {@link RemoteOutageHealth}; never null
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
     * Records that {@code claimOrAbandon} abandoned a permit with no claim attempt because the gate
     * was open (FR14), for the outage's eventual ledger line. A stray call while closed is tolerated
     * rather than asserted against.
     */
    void claimReleasedWhileOpen() {
        counters.claimReleased();
    }

    /**
     * Opens the gate on a slot's {@code InfrastructureUnavailable} result (design D9). A no-op
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
        openedAt = clock.now();
        lastError = scrubbed;
        counters.opened();
        schedule.openedFreshly();
        reporter.opened(scrubbed);
        onTransition.run();
    }

    /**
     * The only path that resets the probe interval to the idle floor (FR14): the first successful
     * base refresh observed after a close. Also records the refresh as the remote's last successful
     * contact.
     *
     * <p>Ignored while the gate is open: the caller reports a slot's terminal result, and a slot
     * that fetched its base before the outage can finish during it — that refresh predates the
     * open, so it is neither "after the close" (it must not shrink the growing probe schedule, nor
     * spend the pending reset the genuine post-close refresh needs) nor evidence the remote is
     * back (recovery is confirmed by a probe, never by a slot).
     */
    synchronized void onSuccessfulRefresh() {
        if (open) {
            return;
        }
        lastSuccessAt = clock.now();
        schedule.onSuccessfulRefresh();
    }

    /**
     * Runs one probe if the gate is open and its scheduled instant has passed; a no-op otherwise. A
     * success closes the gate without resetting the interval (the class javadoc's flapping rule).
     *
     * <p><b>Deliberately not {@code synchronized}</b> ({@code lock-scope.md}, after CERT LCK09-J).
     * The probe is a {@code git ls-remote} subprocess bounded by {@code factory.git-network-timeout}
     * — five minutes by default — and this object's monitor is the one every slot's {@link
     * #openOnFailure}/{@link #onSuccessfulRefresh} signal takes, so holding it across the subprocess
     * stalled exactly the threads whose signals are no-ops while the gate is open. Hence the three
     * phases: decide under the monitor, run the subprocess with nothing held, retake it to record
     * the answer. {@link #probeInFlight} keeps that atomic — a second driver finds the probe running
     * and leaves — and nothing is revalidated on the way back in, deliberately: while a probe is in
     * flight the gate is open and no path can change that (both slot signals return early while
     * open, and {@link #close()} is reached only from here).
     */
    void probeIfDue() {
        if (!beginProbe()) {
            return;
        }
        boolean answered;
        try {
            answered = baseRefGit.probe(cloneDir);
        } catch (RuntimeException e) {
            // The probe never answered, so nothing is recorded — but the latch must not leak, or
            // this gate would never probe again and could only close on a daemon restart.
            abandonProbe();
            throw e;
        }
        applyProbeResult(answered);
    }

    /**
     * Phase one: claims the right to run this cycle's probe. True only when the gate is open, no
     * probe is already running, and the schedule says one is due.
     */
    private synchronized boolean beginProbe() {
        boolean due = open && !probeInFlight && schedule.isDue();
        if (due) {
            probeInFlight = true;
        }
        return due;
    }

    /** Phase three, when the probe threw: releases the latch without recording an answer. */
    private synchronized void abandonProbe() {
        probeInFlight = false;
    }

    /** Phase three: records the answer the probe came back with and releases the latch. */
    private synchronized void applyProbeResult(boolean answered) {
        probeInFlight = false;
        if (answered) {
            close();
        } else {
            onProbeFailed();
        }
    }

    private void onProbeFailed() {
        counters.probeFailed();
        String reason = "origin did not answer ls-remote HEAD";
        lastError = reason;
        schedule.probeFailed();
        reporter.probeFailed(reason);
        Instant since = openedAt;
        if (since != null) {
            reporter.sustainedOpen(since, clock.now(), reason);
        }
    }

    private void close() {
        Instant openedAtSnapshot = openedAt;
        Instant closedAt = clock.now();
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
            onClosedOutage.accept(new RemoteOutageClosedOutage(
                    reporter.target(), openedAtSnapshot, closedAt, probeCount, released, lastErrorSnapshot));
        }
        onTransition.run();
    }

    // PIT documented exception: `lastError` has three writers — openOnFailure, onProbeFailed and
    // close(). openOnFailure is the only one that sets `openedAt`, and it sets `lastError` in the
    // same synchronized transition (never one without the other); onProbeFailed only ever runs
    // while the gate is open, so it replaces a non-null value with another non-null one; close()
    // clears both together. So by the time close() reads it here, `openedAt` being non-null already
    // guarantees `lastError` is non-null too. The "unknown" arm has no reachable input under the
    // class's own invariant; isolated to its own method per the project's convention for an
    // unkillable branch, so it does not hide as a false SURVIVED against close()'s real logic.
    @DoNotMutate
    private static String lastErrorOrUnknown(@Nullable String lastError) {
        return lastError == null ? "unknown" : lastError;
    }
}
