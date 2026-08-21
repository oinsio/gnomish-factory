package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.app.lease.LivenessVerdict;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdict;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener;
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The per-object decision matrix (`sandbox-lifecycle`, "Sweep decision matrix"): minimum-age
 * guard, then ownership × role × state. {@code tracked} unowned is licensed by {@link
 * LivenessVerdict}; {@code manual} skips the oracle entirely and is governed by age alone (FR7) —
 * including its guard/judge/verification/seed-helper objects, which under {@code tracked} mode
 * are disposed on sight (design D2) but under {@code manual} mode have no ownership signal other
 * than age, so they follow the SAME running/stopped age policy as a manual main box rather than
 * risk destroying an active manual session's ancillary containers.
 */
final class SandboxLifecycleDecision {

    private final SandboxLifecycleActions actions;
    private final SweepVerdictListener listener;

    SandboxLifecycleDecision(DockerCli docker, TaskEnvironmentDisposal disposal, SweepVerdictListener listener) {
        this.actions = new SandboxLifecycleActions(docker, disposal);
        this.listener = listener;
    }

    void decideContainer(
            ListedDockerObject object,
            SandboxLifecycleClassification c,
            ObjectTiming timing,
            LivenessVerdict liveness,
            Instant now,
            SandboxLifecycleThresholds thresholds) {
        Duration age = Duration.between(timing.createdAt(), now);
        if (age.compareTo(thresholds.minimumAge()) < 0) {
            emit(c, object.name(), SweepVerdictCategory.CHECKED_ALIVE, "under minimum object age", age);
            return;
        }
        if (trackedGateSettled(object, c, liveness, age)) {
            return;
        }
        handleContainerState(object, c, timing, now, thresholds);
    }

    void decideRemnant(
            ListedDockerObject object,
            SandboxLifecycleClassification c,
            Instant createdAt,
            LivenessVerdict liveness,
            Instant now,
            SandboxLifecycleThresholds thresholds) {
        Duration age = Duration.between(createdAt, now);
        if (age.compareTo(thresholds.minimumAge()) < 0) {
            emit(c, object.name(), SweepVerdictCategory.CHECKED_ALIVE, "under minimum object age", age);
            return;
        }
        if (trackedGateSettled(object, c, liveness, age)) {
            return;
        }
        reapByAge(object, c, age, thresholds, reapReason(c, "remnant"));
    }

    /**
     * The ownership gate both entry points share: a {@code tracked} object is judged by the
     * liveness oracle before its state is ever looked at, and the three answers that settle it
     * outright (no verdict, a fresh claim, an unowned disposable-on-sight role) are decided
     * identically for a container and for a remnant. Kept as one method rather than one per
     * entry point so the two can never drift — the container and remnant paths differ only in
     * what they fall through TO, never in this gate.
     *
     * @return whether the gate emitted the object's verdict, leaving the caller nothing to do
     */
    private boolean trackedGateSettled(
            ListedDockerObject object, SandboxLifecycleClassification c, LivenessVerdict liveness, Duration age) {
        if (c.mode() != OwnershipMode.TRACKED) {
            return false;
        }
        Gate gate = trackedGate(c, liveness);
        if (gate == Gate.SKIPPED) {
            emit(c, object.name(), SweepVerdictCategory.SKIPPED_NO_VERDICT, "no liveness verdict", age);
            return true;
        }
        if (gate == Gate.ALIVE) {
            emit(c, object.name(), SweepVerdictCategory.CHECKED_ALIVE, "fresh claim", age);
            return true;
        }
        if (c.role().disposableOnSight()) {
            disposeAndEmit(object, c, SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE, "unowned " + roleLabel(c), age);
            return true;
        }
        return false;
    }

    private void handleContainerState(
            ListedDockerObject object,
            SandboxLifecycleClassification c,
            ObjectTiming timing,
            Instant now,
            SandboxLifecycleThresholds thresholds) {
        if (timing.running()) {
            // A running box's age is how long it has been RUNNING (started-at, or created-at when
            // the runtime reports no start), which is also the quantity the manual threshold is
            // measured against — so every verdict on this path reports the same number the
            // decision was made from (`sandbox-lifecycle`, "Uniform verdict events": age is a
            // field of every event, not only of the aged-reap ones).
            Instant startedAt = timing.startedAt() != null ? timing.startedAt() : timing.createdAt();
            Duration runningAge = Duration.between(startedAt, now);
            if (c.mode() == OwnershipMode.MANUAL && runningAge.compareTo(thresholds.manualRunningStopAge()) < 0) {
                emit(
                        c,
                        object.name(),
                        SweepVerdictCategory.CHECKED_ALIVE,
                        "manual session within running threshold",
                        runningAge);
                return;
            }
            String reason = c.mode() == OwnershipMode.MANUAL
                    ? "manual running past threshold"
                    : "unowned running " + roleLabel(c);
            if (actions.stop(object)) {
                emit(c, object.name(), SweepVerdictCategory.STOPPED_ORPHAN, reason, runningAge);
            } else {
                emit(c, object.name(), SweepVerdictCategory.SKIPPED_NO_VERDICT, "stop failed: " + reason, runningAge);
            }
            return;
        }
        Instant finishedAt = timing.finishedAt() != null ? timing.finishedAt() : timing.createdAt();
        Duration reapAge = Duration.between(finishedAt, now);
        reapByAge(object, c, reapAge, thresholds, reapReason(c, "stopped"));
    }

    /**
     * The ownership half of an aged-reap reason, shared by the stopped-container and the remnant
     * paths so both read the same way (NFR-O4): a sink separating a routine {@code manual} age
     * reap from a dead-instance symptom reads one vocabulary, not one per path.
     */
    private static String reapReason(SandboxLifecycleClassification c, String state) {
        return (c.mode() == OwnershipMode.MANUAL ? "manual " : "unowned ") + state;
    }

    private void reapByAge(
            ListedDockerObject object,
            SandboxLifecycleClassification c,
            Duration age,
            SandboxLifecycleThresholds thresholds,
            String reason) {
        if (age.compareTo(thresholds.keptReapAge()) < 0) {
            emit(c, object.name(), SweepVerdictCategory.KEPT_UNDER_THRESHOLD, reason, age);
            return;
        }
        disposeAndEmit(object, c, SweepVerdictCategory.DISPOSED_AGED, reason + ", past reap threshold", age);
    }

    /**
     * Acts, then emits what actually happened: a {@code docker} action that failed leaves the
     * object where it was, so it is reported as skipped-no-verdict (the "runtime error during
     * evaluation" row of `sandbox-lifecycle`) rather than as a disposal that never took place.
     */
    private void disposeAndEmit(
            ListedDockerObject object,
            SandboxLifecycleClassification c,
            SweepVerdictCategory category,
            String reason,
            Duration age) {
        if (actions.dispose(c, object)) {
            emit(c, object.name(), category, reason, age);
        } else {
            emit(c, object.name(), SweepVerdictCategory.SKIPPED_NO_VERDICT, "dispose failed: " + reason, age);
        }
    }

    private static Gate trackedGate(SandboxLifecycleClassification c, LivenessVerdict liveness) {
        if (liveness instanceof LivenessVerdict.NoVerdict) {
            return Gate.SKIPPED;
        }
        var live = (LivenessVerdict.Live) liveness;
        return live.environmentKeys().contains(c.baseTaskKey()) ? Gate.ALIVE : Gate.UNOWNED;
    }

    private static String roleLabel(SandboxLifecycleClassification c) {
        return c.role().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private void emit(
            SandboxLifecycleClassification c,
            String name,
            SweepVerdictCategory category,
            String reason,
            @Nullable Duration age) {
        listener.onVerdict(
                new SweepVerdict(category, name, roleLabel(c), c.mode().label(), c.baseTaskKey(), reason, age));
    }

    private enum Gate {
        ALIVE,
        SKIPPED,
        UNOWNED
    }
}
