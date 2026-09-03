package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.lease.StandingReaper;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code serve} SIGTERM shutdown sequence (FR11, design D9, M3): stop claiming immediately,
 * let every in-flight slot stop at its next round boundary within the grace window, release the
 * claims of whichever slots made it, then kill the process tree so no gnome subprocess outlives
 * the daemon regardless of the outcome.
 *
 * <p><b>Reuses the claim-loss round-boundary mechanism verbatim.</b> Step two does not invent a
 * new stop signal: it flags every {@link SlotLedger#occupiedRefs()} entry lost in the SAME {@link
 * ClaimLossFlag} instance the heartbeat already shares with every slot (via {@code
 * TakeHeartbeat}/{@code TakeSlotRunner}), with the shutdown-specific {@link #SHUTDOWN_REASON}. The
 * existing round-boundary check ({@code RevocationCheckingAttemptPersistence}, consulted after
 * every committed engine round) already reacts to a set flag exactly as FR11 requires: salvage any
 * uncommitted leftovers, best-effort push the branch, post a "work stopped" note carrying the
 * reason, and release the claim — an instant return to {@code Ready}. A round that outlives the
 * grace window is deliberately left alone (design risk note: "most SIGTERM stops send mid-round
 * tasks down the TTL path by design") — this sequence still proceeds to kill the process tree and
 * let the process exit; the existing lease/reaper path (add-claim-heartbeat) recovers that task
 * later via its TTL.
 *
 * <p>Idempotent and safe to call more than once or after an already-stopped run: re-flagging an
 * already-lost ref is a no-op ({@link ClaimLossFlag#claimLost(TaskRef, String)} only records the
 * first reason), {@link SlotLedger#awaitDrained(Duration)} on an already-empty ledger returns
 * {@code true} immediately, and killing an already-empty process tree is a no-op. This is what
 * lets ONE JVM shutdown hook safely cover both the SIGTERM path (a running feed thread, slots
 * possibly occupied) and the ordinary post-drain normal-exit path (no feed thread, slots already
 * empty — see {@code ServeCommand}'s wiring).
 *
 * <p>Also stops the {@link StandingReaper} (fix-reaper-idle-liveness FR4) as part of the sequence,
 * right alongside the claim-loss flagging: {@link StandingReaper#stop()} is itself idempotent and
 * safe under concurrent/repeated calls (a {@code volatile stopping} flag plus a worker interrupt),
 * so its exact ordering relative to the other steps is not safety-critical — it just needs to run
 * once per shutdown so the reaper's virtual thread does not outlive the daemon.
 *
 * <p>Implements FR11, D9, M3 of add-factory-serve; FR4 of fix-reaper-idle-liveness.
 */
public record ServeShutdown(
        SlotLedger slotLedger,
        ClaimLossFlag claimLossFlag,
        Duration grace,
        ProcessTreeKiller processTreeKiller,
        StandingReaper standingReaper) {

    private static final Logger log = LoggerFactory.getLogger(ServeShutdown.class);

    /**
     * Design D9: the shutdown-specific reason folded into the round-boundary "work stopped" note.
     * Says "signal", not "SIGTERM": the JVM runs this sequence for SIGINT too, and the note is read
     * by a human on the tracker who would otherwise be told the wrong thing about half the stops
     * (task 3.4 of harden-logging-observability).
     */
    static final String SHUTDOWN_REASON = "daemon shutting down (signal)";

    /**
     * @param slotLedger the shared slot ledger whose {@link SlotLedger#occupiedRefs()} names the
     *     in-flight claims to flag, and whose {@link SlotLedger#awaitDrained(Duration)} is the
     *     grace-window wait; never null
     * @param claimLossFlag the SAME flag instance shared with every slot's round-boundary check;
     *     never null
     * @param grace the configured SIGTERM grace window ({@code factory.serve.sigterm-grace});
     *     positive
     * @param processTreeKiller the final "kill any gnome subprocess" step; never null
     * @param standingReaper the daemon-lifetime standing reaper (fix-reaper-idle-liveness FR1)
     *     stopped as part of this sequence (FR4); never null
     */
    public ServeShutdown {}

    /**
     * Runs the full sequence once: interrupt {@code feedThread} (stop claiming immediately, FR11),
     * flag every occupied slot's claim as gracefully stopping, stop the standing reaper
     * (fix-reaper-idle-liveness FR4), wait up to the configured grace window for the slots to
     * release, then unconditionally kill the process tree.
     *
     * @param feedThread the running feed thread to interrupt first; {@code null} when the caller
     *     has nothing to interrupt (the drain path, whose feed loop already stopped itself before
     *     this runs — see {@code ServeCommand}'s wiring)
     */
    public void shutdown(@Nullable Thread feedThread) {
        if (feedThread != null) {
            feedThread.interrupt();
        }

        Set<TaskRef> occupied = slotLedger.occupiedRefs();
        for (TaskRef ref : occupied) {
            claimLossFlag.claimLost(ref, SHUTDOWN_REASON);
        }
        standingReaper.stop();

        boolean allReleased = awaitDrainedQuietly();
        if (!occupied.isEmpty()) {
            log.info(
                    "serve shutdown: {} in-flight task(s) at SIGTERM, all released within grace={}: {}",
                    occupied.size(),
                    grace,
                    allReleased);
        }

        processTreeKiller.killDescendants();
    }

    private boolean awaitDrainedQuietly() {
        try {
            return slotLedger.awaitDrained(grace);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
