package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
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
 * <p><b>Split of responsibility</b> (process-invariants.md file-size target): probe scheduling is
 * extracted into {@link RemoteOutageProbeSchedule}, the failure/probe/released-claim counts into
 * {@link RemoteOutageCounters}, the sustained-open one-shot latch into {@link
 * RemoteOutageSustainedOpenWatch}, and the observability collaborators (target, suppressor,
 * threshold, callbacks) into the {@link RemoteOutageWiring} parameter object. This class keeps only
 * the open/closed transition, driving each collaborator through its own small interface rather than
 * reaching into its fields.
 *
 * <p><b>Mechanics</b> (the two events FR14 keeps apart): {@link #openOnFailure} opens the gate and
 * calls {@link RemoteOutageProbeSchedule#openedFreshly()}. Each cycle, {@link #probeIfDue()} runs
 * one {@link BaseRefGit#probe} when open and the schedule reports due: a success closes the gate; a
 * failure re-arms the next, longer interval via {@link RemoteOutageProbeSchedule#probeFailed()}, so
 * a gate that closes and reopens before a refresh ever succeeds resumes doubling from where it left
 * off. {@link #onSuccessfulRefresh()} forwards to {@link
 * RemoteOutageProbeSchedule#onSuccessfulRefresh()}, the only path that resets the interval.
 *
 * <p><b>Observability (task 7.4).</b> Opening logs one WARN ({@link RemoteOutageGateLog#opened});
 * every failed probe afterwards logs DEBUG-only via a {@link RepeatSuppressor} keyed by {@link
 * #target} — the WARN fires once per outage, since the state transition (not the suppressor's own
 * "first occurrence" verdict) names the fault. An outage past its threshold logs one ERROR, once.
 * Closing logs one INFO recovery line, calls {@code onTransition} for the snapshot's immediate
 * write, and hands {@code onClosedOutage} the outage's summary for the {@code remoteOutage} ledger
 * line — exactly once per outage (NFR-O1, NFR-O3).
 *
 * <p>Implements FR14, NFR-R3, NFR-O1, NFR-O3, UX6, D9 of add-base-ref-resolution.
 */
public final class RemoteOutageGate {

    /** FR14's own default probe-interval ceiling, absent a configured one (task 7.3 scope note). */
    static final Duration DEFAULT_CAP = Duration.ofMinutes(10);

    /**
     * The default sustained-open ERROR threshold, absent a configured one (task 7.4): an hour is
     * past "will recover shortly" and into "an operator should look", while staying comfortably
     * above the probe cap so a merely slow-to-recover remote does not trip its own backoff.
     */
    static final Duration DEFAULT_SUSTAINED_OPEN_THRESHOLD = Duration.ofHours(1);

    /**
     * The remote-target identity used where no richer one is threaded through: today one daemon
     * runs against exactly one clone/one remote (task 7.3's own scope decision), so a fixed literal
     * is a correct key — multi-remote support gives each gate its own identity instead of widening
     * this one.
     */
    static final String DEFAULT_TARGET = "origin";

    private final BaseRefGit baseRefGit;
    private final Path cloneDir;
    private final Clock clock;
    private final RemoteOutageProbeSchedule schedule;
    private final String target;
    private final RepeatSuppressor suppressor;
    private final RemoteOutageSustainedOpenWatch sustainedOpenWatch;
    private final Runnable onTransition;
    private final Consumer<RemoteOutageClosedOutage> onClosedOutage;

    private final RemoteOutageCounters counters = new RemoteOutageCounters();
    private volatile boolean open;
    private volatile @Nullable Instant openedAt;
    private volatile @Nullable String lastError;
    private volatile @Nullable Instant lastSuccessAt;

    /**
     * Convenience overload for existing specs: delegates to the full constructor below with {@link
     * RemoteOutageWiring#defaults()}.
     */
    RemoteOutageGate(
            BaseRefGit baseRefGit, Path cloneDir, Clock clock, Random random, Duration idleInterval, Duration cap) {
        this(baseRefGit, cloneDir, clock, random, idleInterval, cap, RemoteOutageWiring.defaults());
    }

    /**
     * The full constructor: {@link RemoteOutageWiring} bundles the observability collaborators as
     * one parameter object per the project's parameter-count limit. Package-private like the
     * six-argument overload above; both are reached only through the {@link #system} factories in
     * production. Every parameter is required (never null; durations positive).
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
        this.target = wiring.target();
        this.suppressor = wiring.suppressor();
        this.sustainedOpenWatch = new RemoteOutageSustainedOpenWatch(wiring.sustainedOpenThreshold());
        this.onTransition = wiring.onTransition();
        this.onClosedOutage = wiring.onClosedOutage();
    }

    /**
     * The production gate: real {@link SystemClock}, an unseeded {@link Random}, {@link
     * #DEFAULT_CAP} and {@link RemoteOutageWiring#defaults()} — a fresh daemon starts closed (FR14:
     * "a restart forgets it") and logs to the console only.
     */
    public static RemoteOutageGate system(BaseRefGit baseRefGit, Path cloneDir, Duration idleInterval) {
        return new RemoteOutageGate(
                baseRefGit,
                cloneDir,
                new SystemClock(),
                new Random(),
                idleInterval,
                DEFAULT_CAP,
                RemoteOutageWiring.defaults());
    }

    /**
     * The production gate wired for the composition root (task 7.4): {@code cap} and {@code
     * sustainedOpenThreshold} come from {@code factory.serve.remote-probe-interval-cap} /
     * {@code remote-sustained-open-threshold}; {@code onTransition}/{@code onClosedOutage} let the
     * daemon's own snapshot writer and ledger appender learn about transitions without this class
     * knowing either exists.
     */
    public static RemoteOutageGate system(
            BaseRefGit baseRefGit,
            Path cloneDir,
            Duration idleInterval,
            Duration cap,
            Duration sustainedOpenThreshold,
            Runnable onTransition,
            Consumer<RemoteOutageClosedOutage> onClosedOutage) {
        return new RemoteOutageGate(
                baseRefGit,
                cloneDir,
                new SystemClock(),
                new Random(),
                idleInterval,
                cap,
                new RemoteOutageWiring(
                        DEFAULT_TARGET,
                        RepeatSuppressor.system(),
                        sustainedOpenThreshold,
                        onTransition,
                        onClosedOutage));
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
                target,
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
     * <p>{@code synchronized}: multiple slots run on their own virtual threads and can genuinely
     * discover the same dead remote at the same instant (task 7.5's own end-to-end scenario), so
     * the open-check-then-set above must be atomic against a concurrent caller — an unsynchronized
     * check-then-act here let two simultaneous callers both observe {@code open == false} and both
     * transition, producing two WARNs for one outage. The lock is shared with {@link
     * #onSuccessfulRefresh()} and {@link #probeIfDue()} since all three read-modify-write the same
     * open/schedule/counter state.
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
        sustainedOpenWatch.reset();
        schedule.openedFreshly();
        RemoteOutageGateLog.opened(target, scrubbed);
        // A First occurrence is expected here since a fresh outage's key was either never reported
        // or was cleared by the previous outage's recovered() call — logged at DEBUG only, since
        // the WARN above already named the fault; see the class javadoc's "exactly once" note.
        RemoteOutageGateLog.probeFailed(target, suppressor.failed(suppressorKey(), scrubbed));
        onTransition.run();
    }

    /**
     * The only path that resets the probe interval to the idle floor (FR14): the first successful
     * base refresh observed after a close. Also records the refresh as the remote's last successful
     * contact.
     */
    synchronized void onSuccessfulRefresh() {
        lastSuccessAt = clock.now();
        schedule.onSuccessfulRefresh();
    }

    /**
     * Runs one probe if the gate is open and its scheduled instant has passed; a no-op otherwise.
     * Closing the gate on success does NOT reset the interval — see {@link #onSuccessfulRefresh()}
     * — so a flapping remote (probe passes, refresh fails) reopens at a longer interval than the
     * one it closed at, never at the idle floor.
     */
    synchronized void probeIfDue() {
        if (!open) {
            return;
        }
        if (!schedule.isDue()) {
            return;
        }
        if (baseRefGit.probe(cloneDir)) {
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
        RemoteOutageGateLog.probeFailed(target, suppressor.failed(suppressorKey(), reason));
        Instant since = openedAt;
        if (since != null && sustainedOpenWatch.shouldFire(since, clock.now())) {
            RemoteOutageGateLog.sustainedOpen(target, sustainedOpenWatch.threshold(), reason);
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
        suppressor.recovered(suppressorKey());
        if (openedAtSnapshot != null) {
            RemoteOutageGateLog.closed(target, Duration.between(openedAtSnapshot, closedAt), probeCount);
            onClosedOutage.accept(new RemoteOutageClosedOutage(
                    target, openedAtSnapshot, closedAt, probeCount, released, lastErrorSnapshot));
        }
        onTransition.run();
    }

    private String suppressorKey() {
        return "remote-outage:" + target;
    }

    // PIT documented exception: openOnFailure and close() are the only writers of `lastError`, and
    // they always set it together with `openedAt` (never one without the other) and clear it to null
    // together on close — so by the time close() reads it here, `openedAt` being non-null already
    // guarantees `lastError` is non-null too. The "unknown" arm has no reachable input under the
    // class's own invariant; isolated to its own method per the project's convention for an
    // unkillable branch, so it does not hide as a false SURVIVED against close()'s real logic.
    @DoNotMutate
    private static String lastErrorOrUnknown(@Nullable String lastError) {
        return lastError == null ? "unknown" : lastError;
    }
}
