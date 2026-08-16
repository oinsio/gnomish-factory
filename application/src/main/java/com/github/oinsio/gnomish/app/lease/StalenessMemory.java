package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The core staleness policy (design D2): a per-claim observation memory that, fed a
 * stream of {@code listOpen} results over time, decides which held claims have gone
 * stale by <em>local observation of claim versions</em> — never by comparing the
 * instance's clock to the tracker's {@code updatedAt} (D2 forbids cross-host clock
 * arithmetic). It is pure policy: the reaper (task 4.3) drives it each heartbeat tick
 * — {@code listOpen} → {@link #observe} → {@code removeStaleClaim} for each returned
 * {@link StaleClaim} — and this class knows nothing of HTTP, threads, or tracker
 * physics.
 *
 * <p><b>The rule.</b> For every eligible claim — a {@code Working} task carrying a
 * non-null {@link ClaimVersion} — the memory records the version and the monotonic
 * instant of the observer's <em>own first sighting</em> of it. A claim is stale when
 * that version has stood unchanged for the TTL, measured as {@code nanoTime() −
 * firstSeenNanos ≥ ttl.toNanos()} on the injected {@link MonotonicTime}. Two
 * properties fall out by construction:
 *
 * <ul>
 *   <li><b>Grace period.</b> First-seen is keyed off this observer's own clock, never
 *       off {@code updatedAt}, so a fresh observer that meets a claim whose server
 *       timestamp is already old still gives it a full TTL from first sight before
 *       judging it stale (FR2 "grace period by construction").
 *   <li><b>Beaten claim never stale.</b> When the version changes — a beat refreshed
 *       {@code updatedAt}, or a takeover minted a new marker — the first-seen timer
 *       resets to the new version's first sighting, so a claim beaten within every
 *       TTL window is never classified stale (FR2, NFR-R1).
 * </ul>
 *
 * <p><b>Emission is once per version.</b> {@link #observe} returns only the claims
 * that <em>crossed</em> the TTL threshold on this tick — a claim is emitted exactly
 * once for a given version, not on every subsequent tick it remains stale. This keeps
 * the reaper from firing redundant {@code removeStaleClaim} calls at the same dead
 * claim every interval: the happy path is emit → remove → the task leaves {@code
 * Working} and vanishes from the next {@code listOpen}; and if the version later
 * changes (a live beat or takeover), the timer and the emitted latch reset together,
 * so a newly-stale later version is emitted afresh.
 *
 * <p><b>No leak.</b> Each {@link #observe} forgets every claim not present as an
 * eligible entry in the current {@code listOpen}: a task that closed, returned to
 * {@code Ready}, or lost its claim marker drops out of memory, and if it reappears its
 * timer restarts from that later first sighting.
 *
 * <p>Stateful and not thread-safe: one memory belongs to one reaper loop on the
 * heartbeat thread (task 4.3), which calls {@link #observe} sequentially.
 *
 * <p>The TTL ({@code multiplier × beat interval}, design D8) is taken as a
 * constructor {@link Duration} — the config wiring that derives it from {@code
 * tracker.heartbeat-interval} and {@code tracker.heartbeat-ttl-multiplier} is task
 * 5.1; this class never hardcodes it.
 *
 * <p>Implements FR2, NFR-R1 of add-claim-heartbeat.
 */
public final class StalenessMemory {

    private final MonotonicTime time;
    private final long ttlNanos;
    private final Map<TaskRef, Observation> observations = new HashMap<>();

    /**
     * @param time the monotonic time source TTL is measured on; never null
     * @param ttl the staleness threshold ({@code multiplier × beat interval}, D8);
     *     must be strictly positive — a zero or negative TTL would flag every claim
     *     stale on first sight
     */
    public StalenessMemory(MonotonicTime time, Duration ttl) {
        this.time = time;
        this.ttlNanos = requirePositive(ttl).toNanos();
    }

    /**
     * Records this tick's {@code listOpen} observation and returns the claims that
     * just crossed the TTL threshold (see the class contract for the once-per-version
     * emission and grace-period semantics). Only {@code Working} entries with a
     * non-null {@link ClaimVersion} are ever eligible; {@code AwaitingHuman} entries
     * and {@code Working} entries with an absent claim marker are never stale. Claims
     * absent from {@code openTasks} are forgotten.
     *
     * <p>Implements FR2, NFR-R1 of add-claim-heartbeat.
     *
     * @param openTasks the current {@code listOpen} result; never null
     * @return the claims newly judged stale on this tick, in {@code openTasks} order;
     *     never null, empty when none crossed the threshold
     */
    public List<StaleClaim> observe(List<OpenTask> openTasks) {
        long now = time.nanoTime();
        Map<TaskRef, ClaimVersion> eligible = eligibleClaims(openTasks);
        observations.keySet().retainAll(eligible.keySet());

        List<StaleClaim> newlyStale = new ArrayList<>();
        for (var entry : eligible.entrySet()) {
            TaskRef ref = entry.getKey();
            ClaimVersion version = entry.getValue();
            Observation observation = observations.get(ref);
            if (observation == null || !observation.version.equals(version)) {
                observation = new Observation(version, now);
                observations.put(ref, observation);
            }
            if (now - observation.firstSeenNanos >= ttlNanos && !observation.emitted) {
                observation.emitted = true;
                newlyStale.add(new StaleClaim(ref, version));
            }
        }
        return List.copyOf(newlyStale);
    }

    /**
     * Discards every observation window, so the next {@link #observe} treats every claim as
     * first-seen at that later instant and its TTL restarts from scratch. The reaper (task
     * 4.3) calls this when a {@code listOpen} outage denies it an observation: TTL is measured
     * between observations, so an interrupted stream must not let a pre-outage window keep
     * running on the monotonic clock while the tracker is unreachable. On recovery every claim
     * therefore gets a fresh TTL window from its next observation — a holder that also lost
     * tracker access and has not yet re-beaten is never falsely reaped for time that elapsed
     * while no observer could read versions (FR9 "recovery restarts windows", design D2). The
     * cost is that a genuinely dead claim needs one fresh TTL after recovery to be reaped;
     * safety outranks that promptness.
     *
     * <p>Implements FR9 of add-claim-heartbeat.
     */
    public void forgetAll() {
        observations.clear();
    }

    /**
     * Re-arms the once-per-version emission latch for a claim whose removal failed, so the
     * SAME unchanged version is emitted again on the next {@link #observe} instead of staying
     * silent until the version changes or the memory is dropped. The reaper (task 4.3) calls
     * this after a {@code removeStaleClaim} infrastructure failure (design D14): {@link
     * #observe} latches emission before the removal is even attempted, so without re-arming a
     * failed removal would leave the claim {@code Working} yet un-emitted forever. Guarded by
     * the observed version — a claim whose current observation has since moved to a different
     * version (a live beat or takeover reset the timer) is left untouched, so a stale-but-
     * superseded version can never resurrect the current one.
     *
     * <p>Implements FR4 of add-claim-heartbeat.
     *
     * @param claim the claim (ref + the exact version that failed to remove) to re-arm; never
     *     null
     */
    public void retryEmission(StaleClaim claim) {
        Observation observation = observations.get(claim.ref());
        if (observation != null && observation.version.equals(claim.version())) {
            observation.emitted = false;
        }
    }

    /** The eligible claims in {@code listOpen} order: {@code Working} with a non-null version. */
    private static Map<TaskRef, ClaimVersion> eligibleClaims(List<OpenTask> openTasks) {
        Map<TaskRef, ClaimVersion> eligible = new LinkedHashMap<>();
        for (OpenTask task : openTasks) {
            if (task.state() instanceof TrackerTaskState.Working && task.claimVersion() != null) {
                eligible.put(task.ref(), task.claimVersion());
            }
        }
        return eligible;
    }

    private static Duration requirePositive(Duration ttl) {
        if (ttl.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException("StalenessMemory ttl must be strictly positive, got " + ttl);
        }
        return ttl;
    }

    /**
     * One observed claim's memory: the version seen, the monotonic first-sighting
     * instant TTL is measured from, and whether this version has already been emitted
     * as stale (the once-per-version latch). Mutable and private — replaced wholesale
     * when the version changes, so first-seen and the latch reset together.
     */
    private static final class Observation {

        private final ClaimVersion version;
        private final long firstSeenNanos;
        private boolean emitted;

        private Observation(ClaimVersion version, long firstSeenNanos) {
            this.version = version;
            this.firstSeenNanos = firstSeenNanos;
        }
    }
}
