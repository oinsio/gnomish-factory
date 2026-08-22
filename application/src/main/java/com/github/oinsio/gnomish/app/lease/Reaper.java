package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link ReaperDuty} (design D4): on each heartbeat tick, AFTER the instance has
 * beaten every claim it holds, the thread runs this once. One reap: {@code listOpen} → feed
 * the {@link StalenessMemory} → {@code removeStaleClaim} for each claim the memory just
 * judged stale. The reaper NEVER claims a reaped task for itself — the removal alone returns
 * the task to {@code Ready}, whence it re-enters the ordinary lease queue (FR4, design D5).
 *
 * <p><b>Convergence.</b> {@code removeStaleClaim} is guarded by the observed version and is
 * idempotent in effect: a racing reaper or a live beat that changed the claim since the
 * observation yields {@link RemoveStaleClaimResult.Mismatch}, a safe no-op the reaper does
 * NOT treat as an error — two instances reaping the same stale claim converge to a single
 * {@code Ready} transition without coordination (NFR-R2, design D5).
 *
 * <p><b>Outage.</b> When {@code listOpen} throws (network/5xx), the reaper forgets every
 * observation window ({@link StalenessMemory#forgetAll()}) and skips the tick: no observation
 * means no staleness progress, and dropping the pre-outage windows makes recovery restart each
 * claim's TTL from its first post-outage sighting. This is FR9's "recovery restarts windows" —
 * a holder that also lost tracker access and has not yet re-beaten gets a fresh grace window
 * instead of being reaped for a window that elapsed on the monotonic clock while no observer
 * could read versions. Safety (never falsely reap a live claim) outranks promptness (a
 * genuinely dead claim then costs one fresh TTL after recovery to reap). The next tick retries.
 * A {@code removeStaleClaim} infrastructure failure on ONE claim is caught per-claim so the
 * remaining stale claims are still reaped this tick.
 *
 * <p>A genuinely unexpected exception would propagate to the heartbeat thread's per-tick
 * guard (task 4.2), which keeps the thread alive; the two handled cases above are the only
 * expected failures. Not thread-safe: one reaper belongs to one reaper thread that calls {@link
 * #reapOnce(Collection)} sequentially, and is the sole WRITER of its {@link StalenessMemory} — that
 * memory is additionally read by the sandbox-lifecycle tick thread and synchronizes for it. Task 6.1
 * constructs the reaper with the take run's {@link Tracker} and a per-run {@link StalenessMemory}.
 *
 * <p>Implements FR4, FR9, NFR-R2 of add-claim-heartbeat.
 */
// A final class, not a record: PIT's Gregor engine RUN_ERRORs (crashes its minion JVM) when
// mutating a record here — the JVMTI RedefineClasses restriction on record classes
// (hcoles/pitest#1285), test-independent, not a real coverage gap. Reaper is never compared or
// hashed, so as a plain class its methods mutate and are killed normally.
public final class Reaper implements ReaperDuty {

    private static final Logger log = LoggerFactory.getLogger(Reaper.class);

    private final Tracker tracker;
    private final StalenessMemory memory;
    private final OpenTaskListingSink listingSink;

    /**
     * @param tracker the port the reaper lists open tasks through and removes stale claims
     *     with; never null
     * @param memory the per-run staleness policy fed each successful observation; never null
     */
    public Reaper(Tracker tracker, StalenessMemory memory) {
        this(tracker, memory, OpenTaskListingSink.NONE);
    }

    /**
     * @param tracker the port the reaper lists open tasks through and removes stale claims
     *     with; never null
     * @param memory the per-run staleness policy fed each successful observation; never null
     * @param listingSink taps this tick's {@code listOpen} result (or failure) so a consumer —
     *     the liveness oracle (task 2.1 of add-serve-sandbox-lifecycle) — can reuse it without a
     *     second tracker call; never null, {@link OpenTaskListingSink#NONE} if unused
     */
    public Reaper(Tracker tracker, StalenessMemory memory, OpenTaskListingSink listingSink) {
        this.tracker = tracker;
        this.memory = memory;
        this.listingSink = listingSink;
    }

    /**
     * Runs the reaper once for this tick (FR4). Lists open tasks, drops the instance's own
     * held claims (design D13), feeds the rest to the staleness memory, and removes every
     * newly-stale claim. On a {@code listOpen} outage it forgets all observation windows so
     * recovery restarts them (FR9, design D2); a per-claim removal failure never stops the
     * rest and re-arms that claim for a later retry (design D14).
     *
     * <p>Implements FR4, FR9 of add-claim-heartbeat.
     *
     * @param ownClaims the claims the instance currently holds, excluded from observation so
     *     a live holder never reaps itself; never null, may be empty
     */
    @Override
    public void reapOnce(Collection<TaskRef> ownClaims) {
        List<OpenTask> openTasks;
        try {
            openTasks = tracker.listOpen();
        } catch (RuntimeException e) {
            // Tracker outage: forget the observation windows so recovery restarts each claim's
            // TTL from its first post-outage sighting (FR9 "recovery restarts windows", D2). No
            // observation is fed, so no staleness accrues; and no pre-outage window survives to
            // falsely reap a live holder that also lost tracker access and has not re-beaten.
            log.warn("listOpen failed; forgetting observation windows, recovery restarts them", e);
            memory.forgetAll();
            listingSink.onListingFailed();
            return;
        }
        // Publish the full listing (own claims still included) BEFORE the exclusion below, so a
        // consumer of the sink sees the same tick's listOpen result the reaper itself acted on —
        // no second tracker call (design D1, NFR-C2 of add-serve-sandbox-lifecycle).
        listingSink.onListed(openTasks);
        // Exclude the instance's own held claims BEFORE observation (design D13): a run whose
        // beats are failing while its listOpen still succeeds would otherwise watch its own
        // unchanged version cross the TTL and reap its own live claim. Only a foreign observer
        // may reap this instance; the instance itself knows it is alive.
        Set<TaskRef> own = Set.copyOf(ownClaims);
        List<OpenTask> foreign =
                openTasks.stream().filter(task -> !own.contains(task.ref())).toList();
        for (StaleClaim claim : memory.observe(foreign)) {
            reap(claim);
        }
    }

    private void reap(StaleClaim claim) {
        try {
            RemoveStaleClaimResult result = tracker.removeStaleClaim(claim.ref(), claim.version());
            if (result instanceof RemoveStaleClaimResult.Mismatch) {
                // A racing reaper or a live beat changed the claim; a safe no-op, not an error.
                log.info(
                        "stale claim {} already changed; nothing removed, converging",
                        claim.ref().id());
            }
        } catch (RuntimeException e) {
            // Infrastructure failure removing ONE claim must not stop reaping the others, and
            // must not silence it: re-arm the once-per-version latch (design D14) so the same
            // unchanged version is retried next tick instead of staying Working until it changes.
            log.warn(
                    "removeStaleClaim failed for {}; continuing with the rest",
                    claim.ref().id(),
                    e);
            memory.retryEmission(claim);
        }
    }
}
