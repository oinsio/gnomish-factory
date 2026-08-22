package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.app.lease.LivenessVerdict;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener;
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The shared sweep-lifecycle decision-matrix evaluator (`sandbox-lifecycle`, design D1/D2/D6):
 * ownership × role × state per object, the minimum-age guard, and project scoping at listing.
 * Every entry point (`run`, `take`, `serve`) evaluates through this one component and differs
 * only in the injected {@link SweepVerdictListener} sink.
 *
 * <p>A runtime outage — or any listing that could not be obtained — aborts the whole pass with
 * {@link DockerUnavailableException}: the objects are not enumerable, so nothing is touched and
 * nothing is logged per-object. Fail-closed is the point: a half-read world would judge live
 * objects to be remnants (NFR-R1). The exception reaches the caller rather than being swallowed
 * here, so an aborted pass completes NO tick — a daemon outage must not publish an empty tally as
 * a healthy zero-work tick, which would reset the skipped-tick run length and hide a permanent
 * outage from both the tick-overdue and consecutive-skipped alerts (NFR-O3). Every caller treats
 * it as an infrastructure failure that never blocks the run. A {@code
 * liveness}=={@link LivenessVerdict.NoVerdict} affects only {@code tracked} objects — {@code
 * manual} objects are governed by age alone and need no tracker at all (FR7).
 *
 * <p>Implements FR4, FR5, FR7, FR8, FR9, NFR-C1, NFR-R1, NFR-R2, NFR-S2 of
 * add-serve-sandbox-lifecycle.
 */
public final class SandboxLifecycleSweep {

    private final SandboxLifecycleObjectReader reader;
    private final SandboxLifecycleDecision decision;

    SandboxLifecycleSweep(DockerCli docker, TaskEnvironmentDisposal disposal, SweepVerdictListener listener) {
        this.reader = new SandboxLifecycleObjectReader(docker);
        this.decision = new SandboxLifecycleDecision(docker, disposal, listener);
    }

    /**
     * The production entry point for {@code application}/{@code bootstrap} code (task 4.x of
     * add-serve-sandbox-lifecycle): {@link DockerCli} and {@link ContainerEnvironmentDisposal} are
     * deliberately package-private (the same "app-layer names only the environment-facing types"
     * discipline as {@link ContainerEnvironments#forTask}), so this static factory is the only way
     * to obtain a usable evaluator from outside this package.
     *
     * @param listener the verdict sink; never null
     * @return a sweep over the real {@code docker} binary; never null
     */
    public static SandboxLifecycleSweep create(SweepVerdictListener listener) {
        DockerCli docker = new DockerCli();
        return new SandboxLifecycleSweep(docker, new ContainerEnvironmentDisposal(docker), listener);
    }

    /**
     * Evaluates every factory-labelled object of this project and acts on it (`sandbox-lifecycle`).
     *
     * @param projectId this factory's project identity — objects of any other project are
     *     excluded at listing (FR8); never blank
     * @param liveness the current tracked-object liveness verdict; never null
     * @param now the instant every object's age is measured against; never null
     * @param thresholds the minimum/reap/manual-running-stop durations; never null
     */
    public void evaluate(
            String projectId, LivenessVerdict liveness, Instant now, SandboxLifecycleThresholds thresholds) {
        List<ListedDockerObject> containers =
                reader.list(ObjectKind.CONTAINER, DockerLifecycleCommands.listFactoryContainersWithLabels(projectId));
        List<ListedDockerObject> volumes =
                reader.list(ObjectKind.VOLUME, DockerLifecycleCommands.listFactoryVolumesWithLabels(projectId));
        List<ListedDockerObject> networks =
                reader.list(ObjectKind.NETWORK, DockerLifecycleCommands.listFactoryNetworksWithLabels(projectId));
        Set<String> mainBoxKeysWithContainer = mainBoxKeys(containers);

        containers.forEach(o -> evaluateContainer(o, liveness, now, thresholds));
        volumes.forEach(o -> evaluateRemnant(o, liveness, now, thresholds, mainBoxKeysWithContainer));
        networks.forEach(o -> evaluateRemnant(o, liveness, now, thresholds, mainBoxKeysWithContainer));
    }

    private Set<String> mainBoxKeys(List<ListedDockerObject> containers) {
        return containers.stream()
                .map(SandboxLifecycleClassification::of)
                .filter(c -> c != null && c.role() == ObjectRole.MAIN_BOX)
                .map(SandboxLifecycleClassification::environmentKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void evaluateContainer(
            ListedDockerObject object, LivenessVerdict liveness, Instant now, SandboxLifecycleThresholds thresholds) {
        SandboxLifecycleClassification c = SandboxLifecycleClassification.of(object);
        if (c == null) {
            return;
        }
        reader.containerTiming(object.name())
                .ifPresent(timing -> decision.decideContainer(object, c, timing, liveness, now, thresholds));
    }

    private void evaluateRemnant(
            ListedDockerObject object,
            LivenessVerdict liveness,
            Instant now,
            SandboxLifecycleThresholds thresholds,
            Set<String> mainBoxKeysWithContainer) {
        SandboxLifecycleClassification c = SandboxLifecycleClassification.of(object);
        if (c == null) {
            return;
        }
        if (c.role() == ObjectRole.MAIN_BOX && mainBoxKeysWithContainer.contains(c.environmentKey())) {
            return; // governed entirely by its container's own verdict (design D2)
        }
        List<String> inspectArgv = object.kind() == ObjectKind.VOLUME
                ? DockerLifecycleCommands.inspectVolumeCreatedAt(object.name())
                : DockerLifecycleCommands.inspectNetworkCreatedAt(object.name());
        reader.createdAt(object.name(), inspectArgv)
                .ifPresent(createdAt -> decision.decideRemnant(object, c, createdAt, liveness, now, thresholds));
    }
}
