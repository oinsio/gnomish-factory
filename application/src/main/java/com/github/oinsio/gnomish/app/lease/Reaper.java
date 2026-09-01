package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts;
import com.github.oinsio.gnomish.logtext.MdcAwareThread;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link ReaperDuty} (design D4, generalized by D16): one tick is one sweep of the whole
 * sweep universe — {@code listReady} plus {@code listOpen} — classified through {@link
 * TrackerShapeClassifier}, timed by the {@link StalenessMemory}, and repaired through the port
 * operation each released shape's recovery names. The union is the point: a sweeper filtering on
 * the very label a kill window may not have written yet is structurally blind, so the ready feed's
 * claim facts are swept beside the open listing's.
 *
 * <p><b>Repairs.</b> A dead tenure ({@code Claimed} past its TTL) and an abandoned footprint go
 * through {@code removeStaleClaim}; a pending claim and a lagging index go through {@code
 * repairIndex}. The reaper NEVER claims a repaired task for itself — the repair alone returns it to
 * circulation, whence it re-enters the ordinary lease queue (FR4, design D5).
 *
 * <p><b>Convergence.</b> Both repairs are guarded by the observed facts and idempotent in effect: a
 * racing reaper or a live beat that moved the facts yields a mismatch or unchanged result, a safe
 * no-op the reaper does NOT treat as an error (NFR-R2, design D5).
 *
 * <p><b>Outage.</b> When either listing throws, the reaper forgets every observation window ({@link
 * StalenessMemory#forgetAll()}) and skips the tick: no observation means no progress toward any
 * threshold, and dropping pre-outage windows makes recovery restart each timer from its first
 * post-outage sighting (FR9). A repair failure on ONE task is caught per-task so the rest still
 * run, and re-arms that task for a later tick.
 *
 * <p>Not thread-safe: one reaper belongs to one reaper thread that calls {@link
 * #reapOnce(Collection)} sequentially, and is the sole WRITER of its {@link StalenessMemory}.
 *
 * <p>Implements FR4, FR9, NFR-R2 of add-claim-heartbeat; FR19, FR12 of harden-task-branch-contract.
 */
// A final class, not a record: PIT's Gregor engine RUN_ERRORs (crashes its minion JVM) when
// mutating a record here — the JVMTI RedefineClasses restriction on record classes
// (hcoles/pitest#1285), test-independent, not a real coverage gap.
public final class Reaper implements ReaperDuty {

    private static final Logger log = LoggerFactory.getLogger(Reaper.class);

    /**
     * How many ready entries one sweep enumerates. The ready feed is a limited call by contract;
     * this bound is the sweep's own, and a full page is logged rather than silently truncated, so a
     * backlog deeper than one page is visible instead of looking like full coverage.
     */
    private static final int READY_SWEEP_LIMIT = 100;

    private final Tracker tracker;
    private final StalenessMemory memory;
    private final OpenTaskListingSink listingSink;

    /**
     * @param tracker the port the reaper sweeps and repairs through; never null
     * @param memory the per-run observation memory fed each successful sweep; never null
     */
    public Reaper(Tracker tracker, StalenessMemory memory) {
        this(tracker, memory, OpenTaskListingSink.NONE);
    }

    /**
     * @param tracker the port the reaper sweeps and repairs through; never null
     * @param memory the per-run observation memory fed each successful sweep; never null
     * @param listingSink taps this tick's {@code listOpen} result (or failure) so the liveness
     *     oracle can reuse it without a second tracker call; never null
     */
    public Reaper(Tracker tracker, StalenessMemory memory, OpenTaskListingSink listingSink) {
        this.tracker = tracker;
        this.memory = memory;
        this.listingSink = listingSink;
    }

    /**
     * Runs one sweep tick (FR4, FR19): enumerates the union of both listings, drops the instance's
     * own held claims, classifies and times the rest, and repairs everything the memory releases.
     *
     * <p>Implements FR4, FR9 of add-claim-heartbeat; FR19 of harden-task-branch-contract.
     *
     * @param ownClaims the claims the instance currently holds, excluded from observation so a live
     *     holder never reaps itself; never null, may be empty
     */
    @Override
    public void reapOnce(Collection<TaskRef> ownClaims) {
        List<OpenTask> openTasks;
        List<ReadyTask> readyTasks;
        try {
            openTasks = tracker.listOpen();
            readyTasks = tracker.listReady(READY_SWEEP_LIMIT);
        } catch (RuntimeException e) {
            // Tracker outage: forget the observation windows so recovery restarts every timer from
            // its first post-outage sighting (FR9, D2). No observation is fed, so nothing accrues,
            // and no pre-outage window survives to falsely repair a state that has since moved.
            log.warn("sweep listing failed; forgetting observation windows, recovery restarts them", e);
            memory.forgetAll();
            listingSink.onListingFailed();
            return;
        }
        // Publish the full open listing (own claims still included) BEFORE the exclusion below, so
        // a consumer of the sink sees the same tick's result the reaper itself acted on — no second
        // tracker call (design D1, NFR-C2 of add-serve-sandbox-lifecycle).
        listingSink.onListed(openTasks);
        if (readyTasks.size() == READY_SWEEP_LIMIT) {
            // FR12 of harden-logging-observability: a full sweep page is the normal shape of a
            // busy backlog, not a state change — every tick of a healthy busy factory would say
            // it. Reconciliation chatter belongs to whoever is diagnosing a sweep, at DEBUG.
            log.debug(
                    "ready feed filled the sweep page of {}; deeper entries wait for a later tick", READY_SWEEP_LIMIT);
        }
        // Exclude the instance's own held claims BEFORE observation (design D13): a run whose beats
        // are failing while its listings still succeed would otherwise watch its own unchanged
        // version cross the TTL and reap its own live claim. Only a foreign observer may reap this
        // instance; the instance itself knows it is alive.
        Set<TaskRef> own = Set.copyOf(ownClaims);
        List<TrackerObservation> sweep = TrackerObservation.sweep(notOwn(readyTasks, own), openFacts(openTasks, own));
        reportForeign(sweep);
        for (TrackerRepair repair : memory.observe(sweep)) {
            repair(repair);
        }
    }

    /**
     * Surfaces every {@code Foreign} observation with its diagnosis (FR19). The shape has no
     * recovery owner — no automatic repair may touch an out-of-protocol combination — so reporting
     * it IS the sweep's whole duty for it, and the listing contract guarantees adapters really
     * report such combinations rather than omitting what they cannot interpret. Warned once per
     * tick per task: a foreign task that nobody fixes stays visible instead of scrolling away.
     */
    private static void reportForeign(List<TrackerObservation> sweep) {
        for (TrackerObservation observation : sweep) {
            if (observation.shape() instanceof TrackerShape.Foreign(String diagnosis)) {
                // FR8/UX2: the sweep's thread carries no task scope, so each per-task line names
                // its subject — a reap belongs to that task's `grep taskId=<id>` story.
                try (var scope = MdcAwareThread.taskScope(observation.ref().id())) {
                    log.warn(
                            "{} classifies foreign; no automatic repair owns it: {}",
                            observation.ref().id(),
                            diagnosis);
                }
            }
        }
    }

    private static List<ReadyTask> notOwn(List<ReadyTask> readyTasks, Set<TaskRef> own) {
        return readyTasks.stream().filter(task -> !own.contains(task.ref())).toList();
    }

    private static Map<TaskRef, TrackerFacts> openFacts(List<OpenTask> openTasks, Set<TaskRef> own) {
        Map<TaskRef, TrackerFacts> facts = new LinkedHashMap<>();
        for (OpenTask task : openTasks) {
            if (!own.contains(task.ref())) {
                facts.put(task.ref(), task.facts());
            }
        }
        return facts;
    }

    /** Routes one released shape to the port operation its recovery names, converging on a no-op. */
    private void repair(TrackerRepair repair) {
        // FR8/UX2: everything this repair decides — the convergence no-ops and the failure WARN
        // alike — is findable by taskId. The scope must wrap the catch, so the two are nested: a
        // try-with-resources with its own catch clause closes the resource before the catch runs.
        try (var taskScope = MdcAwareThread.taskScope(repair.ref().id())) {
            repairInScope(repair);
        }
    }

    /** The repair itself, running inside {@link #repair}'s task-scoped MDC. */
    private void repairInScope(TrackerRepair repair) {
        try {
            switch (repair.shape()) {
                case TrackerShape.Claimed(ClaimFacts.Live claim) -> removeClaim(repair, claim);
                case TrackerShape.ClaimAbandoned(ClaimFacts claim) -> removeClaim(repair, claim);
                case TrackerShape.ClaimPending() -> repairIndex(repair);
                case TrackerShape.IndexLagging ignored -> repairIndex(repair);
                // The shapes no repair owns, named rather than defaulted: TrackerShape is sealed
                // precisely so a new shape fails THIS switch until its recovery is decided here
                // (design D16), and a `default` is what silently gives it none. Unreachable
                // today — the memory releases only the four shapes above (StalenessMemory's
                // timed set) — so these branches stay empty: a Foreign task is surfaced by
                // reportForeign on the sweep itself, and the steady shapes need nothing done.
                // One type-pattern label per shape, not one grouped record-pattern label: javac
                // compiles the grouped form to an instanceof chain, whose conditionals PIT then
                // reports as uncovered mutations of code no test can reach.
                case TrackerShape.Ready ignoredReady -> {}
                case TrackerShape.Returned ignoredReturned -> {}
                case TrackerShape.Parked ignoredParked -> {}
                case TrackerShape.Finished ignoredFinished -> {}
                case TrackerShape.Revoked ignoredRevoked -> {}
                case TrackerShape.Foreign ignoredForeign -> {}
            }
        } catch (RuntimeException e) {
            // An infrastructure failure repairing ONE task must not stop the others, and must not
            // silence it: re-arm the once-per-shape latch (design D14) so the same unchanged shape
            // is retried next tick instead of staying frozen until its facts change.
            log.warn(
                    "repair failed for {}; continuing with the rest",
                    repair.ref().id(),
                    e);
            memory.retryEmission(repair);
        }
    }

    private void removeClaim(TrackerRepair repair, ClaimFacts claim) {
        if (tracker.removeStaleClaim(repair.ref(), claim) instanceof RemoveStaleClaimResult.Mismatch) {
            // A racing reaper or a live beat changed the claim; a safe no-op, not an error — and
            // under contention the normal one, so it is DEBUG (FR12): convergence is the design
            // working, and the operator plane records the removals that did happen, not the
            // ones another instance got to first.
            log.debug(
                    "claim on {} already changed; nothing removed, converging",
                    repair.ref().id());
        }
    }

    private void repairIndex(TrackerRepair repair) {
        if (tracker.repairIndex(repair.ref(), repair.facts()) instanceof RepairIndexResult.Unchanged) {
            // Same convergence-under-contention shape as removeClaim, and the same level (FR12).
            log.debug(
                    "index of {} already moved; nothing repaired, converging",
                    repair.ref().id());
        }
    }
}
