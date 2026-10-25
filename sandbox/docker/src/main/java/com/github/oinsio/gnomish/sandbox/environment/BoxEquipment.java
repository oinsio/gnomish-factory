package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.nio.file.Path;

/**
 * The installation's box equipment, one value (design D12 of tighten-spec-contract, finding F82):
 * what the installation decides about every box before any task exists — the docker subprocess
 * seam, the operator sandbox config, the box timing, the guard config root, the ownership mode and
 * the image contract check every box image must pass (design D3 of add-sandbox-base-image).
 * {@link ContainerEnvironmentFactory} builds it once; {@link ContainerEnvironments}, {@link
 * ContainerEnvironmentBuilder} and {@link ContainerTaskExecutionEnvironment} take it whole beside
 * a task's own inputs (the key, the box git link, the child-env allowlist, the project identity),
 * so no constructor of the round-environment chain takes the installation's equipment as loose
 * parameters next to a task's.
 *
 * <p>It owns the three projections every box built from it shares, so no consumer composes them
 * on its own: the container settings, a role's guard config directory, and the ownership stamped
 * on a task's objects.
 *
 * <p>Implements FR20 of make-checkpoint-gate-durable; FR13 of tighten-spec-contract; FR5 of
 * add-sandbox-base-image.
 *
 * @param docker the docker subprocess seam every box of the installation runs on; never null
 * @param sandbox the operator sandbox config: image, runtime, limits, guard image, allowlist;
 *     never null
 * @param timing the exec clock, the self-check pause and the docker command bound; never null
 * @param guardConfigRoot the factory-private directory guard configs render under (per
 *     environment key), never inside a working copy or scratch area; never null
 * @param ownershipMode the mode stamped on every object every task built from this equipment
 *     creates (FR2 of add-serve-sandbox-lifecycle); never null
 * @param imageContract the check of the sandbox image's contract label against the contract and
 *     the product version this factory was built with (FR5 of add-sandbox-base-image); never null
 */
record BoxEquipment(
        DockerCli docker,
        SandboxProperties sandbox,
        BoxTiming timing,
        Path guardConfigRoot,
        OwnershipMode ownershipMode,
        ImageContractCheck imageContract) {

    /**
     * The container settings every box of the installation runs with. Built on each call, so an
     * installation without a configured image fails here — when a box is built — and never at
     * construction (host-only installations build no box).
     *
     * @return the image, runtime, limits and disk-quota opt-in; never null
     * @throws IllegalStateException when {@code factory.sandbox.image} is unset
     */
    TaskContainerSettings containerSettings() {
        return new TaskContainerSettings(
                sandbox.image(), sandbox.runtime(), sandbox.limits(), sandbox.enforceDiskQuota());
    }

    /**
     * The ownership every object of one task carries: this installation's mode beside the task's
     * project identity (FR2, FR8 of add-serve-sandbox-lifecycle).
     *
     * @param projectId the project identity of the task; never blank
     * @return the ownership stamped on the task's objects; never null
     */
    ObjectOwnership ownership(String projectId) {
        return new ObjectOwnership(ownershipMode, projectId);
    }

    /**
     * The directory one environment key's guard config renders under.
     *
     * @param key the environment key ({@code <key>}, {@code <key>-j} or {@code <key>-v})
     * @return the key's directory under the guard config root; never null
     */
    Path guardConfig(String key) {
        return guardConfigRoot.resolve(key);
    }
}
