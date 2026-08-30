package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The core timing policy of the sweep (design D2, generalized by D16): a per-task observation
 * memory that, fed the classified {@link TrackerObservation}s of tick after tick, decides which
 * non-steady shapes have stood long enough to repair — by <em>local observation</em>, never by
 * comparing the instance's clock to a tracker timestamp (D2 forbids cross-host clock arithmetic).
 * It is pure policy: the reaper drives it each tick — sweep → {@link #observe} → the port repair
 * each emitted {@link TrackerRepair} names — and this class knows nothing of HTTP, threads, or
 * tracker physics.
 *
 * <p><b>The rule.</b> Every observation whose shape is not steady, plus every held {@code Claimed}
 * tenure, is remembered with the monotonic instant of the observer's <em>own first sighting</em> of
 * that exact shape, and released for repair once that shape has stood unchanged for its own
 * threshold: the reassignment TTL for a {@code Claimed} tenure (its claim version is part of the
 * shape, so a beat resets the timer), the window grace for the two graced window shapes, and no
 * wait at all for {@code IndexLagging} — its marker is already the truth. Two properties fall out:
 * a fresh observer meeting an already-old claim still gives it a full TTL from first sight, and a
 * version change replaces the shape, so first-seen and the emission latch reset together and a
 * beaten claim is never released.
 *
 * <p><b>No eligibility filter.</b> Every enumerated task enters the memory regardless of its claim
 * facts — a dead footprint or an absent claim is never dropped for lacking a live version (FR19).
 * Steady shapes are remembered as nothing: they need no repair, and forgetting them keeps the map
 * bounded by the non-steady set. Emission is once per shape, so the reaper does not re-fire at the
 * same frozen state every interval; {@link #retryEmission} re-arms one entry whose repair failed.
 *
 * <p><b>Threading.</b> Mutated by exactly one writer — the standing reaper's own thread — and read
 * from a second, the daemon's sandbox-lifecycle tick through {@link LivenessOracle}; every method
 * touching the map is therefore {@code synchronized}, and none performs I/O under the lock.
 *
 * <p>Implements FR2, NFR-R1 of add-claim-heartbeat; FR19, FR12 of harden-task-branch-contract.
 */
public final class StalenessMemory {

    private final MonotonicTime time;
    private final long ttlNanos;
    private final long graceNanos;
    private final Map<TaskRef, Observation> observations = new HashMap<>();

    /**
     * The memory with one deadline for both timers — the reassignment TTL also serving as the
     * window grace, for a caller with no separate grace of its own.
     *
     * @param time the monotonic time source both timers are measured on; never null
     * @param ttl the reassignment threshold; must be strictly positive
     */
    public StalenessMemory(MonotonicTime time, Duration ttl) {
        this(time, ttl, ttl);
    }

    /**
     * @param time the monotonic time source both timers are measured on; never null
     * @param ttl the reassignment threshold a {@code Claimed} tenure's version must stand
     *     unchanged for; must be strictly positive
     * @param windowGrace how long a frozen window shape must stand before the reaper repairs it —
     *     the pause that lets an in-flight write sequence finish itself; must be strictly positive
     */
    public StalenessMemory(MonotonicTime time, Duration ttl, Duration windowGrace) {
        this.time = time;
        this.ttlNanos = requirePositive(ttl, "ttl").toNanos();
        this.graceNanos = requirePositive(windowGrace, "windowGrace").toNanos();
    }

    /**
     * Records this tick's sweep and returns the shapes that just crossed their threshold, in sweep
     * order. Observations absent from {@code sweep} are forgotten. Implements FR2 of
     * add-claim-heartbeat; FR19 of harden-task-branch-contract.
     *
     * @param sweep this tick's classified observations; never null
     * @return the repairs released on this tick; never null, possibly empty
     */
    public synchronized List<TrackerRepair> observe(List<TrackerObservation> sweep) {
        long now = time.nanoTime();
        Map<TaskRef, TrackerObservation> timed = timedShapes(sweep);
        observations.keySet().retainAll(timed.keySet());

        List<TrackerRepair> released = new ArrayList<>();
        for (var entry : timed.entrySet()) {
            TrackerObservation seen = entry.getValue();
            Observation observation = observations.get(entry.getKey());
            if (observation == null || !observation.seen.shape().equals(seen.shape())) {
                observation = new Observation(seen, now);
                observations.put(entry.getKey(), observation);
            }
            if (!observation.emitted && now - observation.firstSeenNanos >= thresholdNanos(seen.shape())) {
                observation.emitted = true;
                released.add(new TrackerRepair(entry.getKey(), seen.facts(), seen.shape()));
            }
        }
        return List.copyOf(released);
    }

    /**
     * Discards every observation window, so the next {@link #observe} treats every shape as
     * first-seen at that later instant. The reaper calls this when a listing outage denies it an
     * observation: both timers are measured between observations, so an interrupted stream must not
     * let a pre-outage window keep running while the tracker is unreachable (FR9). A genuinely dead
     * claim then costs one fresh TTL after recovery; safety outranks that promptness. Implements
     * FR9 of add-claim-heartbeat.
     */
    public synchronized void forgetAll() {
        observations.clear();
    }

    /**
     * Re-arms the once-per-shape latch for a repair that failed, so the SAME unchanged shape is
     * emitted again next tick instead of staying silent until the facts change (design D14).
     * Guarded by the shape: an entry that has since moved on is left untouched. Implements FR4 of
     * add-claim-heartbeat; FR19 of harden-task-branch-contract.
     *
     * @param repair the repair (ref + the exact shape) that failed; never null
     */
    public synchronized void retryEmission(TrackerRepair repair) {
        Observation observation = observations.get(repair.ref());
        if (observation != null && observation.seen.shape().equals(repair.shape())) {
            observation.emitted = false;
        }
    }

    /**
     * The refs currently latched for repair — every remembered task whose emission has fired and has
     * not since been superseded or forgotten. The liveness oracle's "unowned" verdict reuses this
     * exact latch, so a task whose repair is still pending classifies unowned too. Implements FR3
     * of add-serve-sandbox-lifecycle.
     *
     * @return the currently-latched refs; never null, possibly empty
     */
    public synchronized Set<TaskRef> staleRefs() {
        return observations.entrySet().stream()
                .filter(entry -> entry.getValue().emitted)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The observations worth timing, in sweep order: the non-steady shapes plus held tenures. */
    private static Map<TaskRef, TrackerObservation> timedShapes(List<TrackerObservation> sweep) {
        Map<TaskRef, TrackerObservation> timed = new LinkedHashMap<>();
        for (TrackerObservation observation : sweep) {
            if (isTimed(observation.shape())) {
                timed.put(observation.ref(), observation);
            }
        }
        return timed;
    }

    /**
     * The shapes this memory times: the ones whose recovery the reaper owns, plus a held {@code
     * Claimed} tenure — owned by its holder for as long as it beats, and timed here precisely to
     * find out that it stopped. Asked of {@link TrackerShape#recoveryOwner()} rather than listed as
     * a whitelist of {@code instanceof} tests: the owner mapping is an exhaustive switch over the
     * sealed set, so a new shape has to name its owner there, and naming {@code REAPER} makes it
     * timed here with no second place to remember. A shape no owner repairs — {@code Foreign} — is
     * deliberately NOT timed: latching it would enter it into {@link #staleRefs()}, which the
     * liveness oracle reads as "unowned", for a task no repair will ever converge.
     */
    private static boolean isTimed(TrackerShape shape) {
        return shape.recoveryOwner() == TrackerRecoveryOwner.REAPER || shape instanceof TrackerShape.Claimed;
    }

    private long thresholdNanos(TrackerShape shape) {
        if (shape instanceof TrackerShape.IndexLagging) {
            return 0L;
        }
        return shape instanceof TrackerShape.Claimed ? ttlNanos : graceNanos;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "StalenessMemory %s must be strictly positive, got %s".formatted(name, value));
        }
        return value;
    }

    /**
     * One observed task's memory: the observation seen (facts and shape), the monotonic
     * first-sighting instant its threshold is measured from, and whether that shape has already
     * been released for repair.
     * Mutable and private — replaced wholesale when the shape changes, so first-seen and the latch
     * reset together.
     */
    private static final class Observation {

        private final TrackerObservation seen;
        private final long firstSeenNanos;
        private boolean emitted;

        private Observation(TrackerObservation seen, long firstSeenNanos) {
            this.seen = seen;
            this.firstSeenNanos = firstSeenNanos;
        }
    }
}
