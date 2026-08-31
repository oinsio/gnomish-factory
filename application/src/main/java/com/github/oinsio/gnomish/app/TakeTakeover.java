package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts;
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The {@code Working}-state takeover path of the explicit-mode disposition (design D9, FR6 of
 * add-claim-heartbeat): a task held by another instance is not a flat refusal but a pre-claim
 * confirmation gate. This helper reads the claim facts (holder from the {@link TrackerTask} state,
 * last-beat age from {@code listOpen}'s {@link ClaimVersion}), asks the {@link TakeoverConfirmation}
 * seam — unless the {@code --takeover} flag already authorized it headlessly — and, on confirmation,
 * returns the stale claim via {@link Tracker#removeStaleClaim} and then claims by the ordinary lease
 * and resumes from the branch, reusing {@link TakeClaimAndWork#claimAndWork} (so the heartbeat
 * starts for the taken-over task exactly as for any other claim). Without confirmation it refuses,
 * naming the holder.
 *
 * <p>The confirmed-race handling is delegated entirely to the ordinary claim: after {@code
 * removeStaleClaim} the run does the same {@code claimAndWork} as a {@code Ready} task, whose {@code
 * claim} re-reads live state. A {@link com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult.Mismatch}
 * (the holder beat between {@code listOpen} and removal) leaves the task {@code Working}, so the
 * following {@code claim} comes back {@code Held} and {@code claimAndWork} refuses naming the current
 * holder; a {@code Removed} that another instance re-claims first likewise refuses on the {@code
 * Held}. Extracted from {@link TakeDisposition} so that class stays within the file-size cap.
 *
 * <p>Implements FR6 of add-claim-heartbeat.
 */
final class TakeTakeover {

    private final TakeClaimAndWork claimAndWork;
    private final TakeoverConfirmation confirmation;
    private final boolean takeoverFlag;
    private final Clock clock;

    /**
     * @param claimAndWork the shared claim-and-resume sequence the confirmed path falls through to,
     *     identical to the {@code Ready} case; never null
     * @param confirmation the pre-claim confirmation seam (production TTY prompt or a test double);
     *     never null
     * @param takeoverFlag whether {@code --takeover} was given — a headless authorization that
     *     bypasses the seam entirely
     * @param clock the take run's clock, used only to render the display-only last-beat age; never null
     */
    TakeTakeover(TakeClaimAndWork claimAndWork, TakeoverConfirmation confirmation, boolean takeoverFlag, Clock clock) {
        this.claimAndWork = claimAndWork;
        this.confirmation = confirmation;
        this.takeoverFlag = takeoverFlag;
        this.clock = clock;
    }

    /**
     * Runs the takeover gate for {@code trackerTask} (already known to be {@code Working} held by
     * {@code holder}) and, when confirmed, the {@code removeStaleClaim} + ordinary claim + resume.
     *
     * <p>Implements FR6 of add-claim-heartbeat.
     */
    TakeResult take(
            Path cloneDir,
            @Nullable String base,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            TrackerTask trackerTask,
            Tracker tracker,
            InstanceId instanceId,
            String holder) {
        TaskRef ref = trackerTask.ref();
        ClaimFacts observed = observedClaim(tracker, ref);
        if (!takeoverFlag) {
            TakeoverConfirmation.Decision decision =
                    confirmation.confirm(ref, holder, lastBeatAge(observed.liveVersion()));
            if (decision != TakeoverConfirmation.Decision.CONFIRMED) {
                return refuse(decision, holder);
            }
        }
        if (!(observed instanceof ClaimFacts.None)) {
            // Removed flips the task to Ready; Mismatch is a safe no-op leaving it Working — either
            // way the ordinary claim below re-reads live state and decides (resume, or refuse Held).
            tracker.removeStaleClaim(ref, observed);
        }
        return claimAndWork.claimAndWork(
                cloneDir, base, definition, interactiveMode, discardWork, trackerTask, tracker, instanceId);
    }

    /** The claim footprint {@code listOpen} reports for {@code ref}, or none when it lists no such task. */
    private static ClaimFacts observedClaim(Tracker tracker, TaskRef ref) {
        for (OpenTask open : tracker.listOpen()) {
            if (open.ref().equals(ref)) {
                return open.facts().claim();
            }
        }
        return new ClaimFacts.None();
    }

    /**
     * The display-only human age of the last beat: {@code Duration.between(updatedAt, now)} on the
     * run's wall clock. This is operator information, never a staleness decision (which compares no
     * clocks — design D2), so wall-clock arithmetic is fine here; an unobservable version reads as
     * {@code unknown}.
     */
    private String lastBeatAge(@Nullable ClaimVersion observed) {
        if (observed == null) {
            return "unknown";
        }
        long seconds = Math.max(
                0, Duration.between(observed.updatedAt(), clock.instant()).toSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    /** Refuses an unconfirmed takeover, always naming the holder; the headless case also points at the flag. */
    private static TakeResult refuse(TakeoverConfirmation.Decision decision, String holder) {
        if (decision == TakeoverConfirmation.Decision.UNAVAILABLE) {
            return new TakeResult.Skipped(
                    "Task is claimed by another instance (" + holder
                            + ") — refusing to take it without confirmation. Re-run with --takeover to take it over headlessly.");
        }
        return new TakeResult.Skipped("Takeover of the task held by " + holder + " was declined — nothing changed.");
    }
}
