package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.ResourceLimits;

/**
 * Everything one {@code docker run} of a task container is built from (design D3 of
 * fix-image-declared-volumes): the parameter object {@link DockerCommands#runContainer} takes
 * instead of the eight positional arguments the declared-volume override would otherwise push it
 * to — over the seven-parameter limit of {@code process-invariants.md}, which asks that the
 * parameter object land with the change that would create the offender.
 *
 * <p>It is also what makes the override mechanism single-owner: {@link #overrides} is a required
 * component, so a caller that skipped resolving them does not compile (FR3, {@code
 * implementation.md} item 3). Nothing here is assembled from environment-originated content —
 * every value is a factory-sanitized key, an operator-configured setting, or a value read from
 * the image at creation time.
 *
 * @param key the sanitized environment key naming this task's objects; never blank
 * @param image the image reference the container runs; never blank
 * @param runtime the configured container runtime ({@code factory.sandbox.runtime})
 * @param limits the CPU/memory/PID/disk bounds to apply (FR10 of add-sandbox-core)
 * @param enforceDiskQuota whether to add {@code --storage-opt size=}, which needs a
 *     quota-capable storage driver and so stays opt-in
 * @param workingCopy the in-container path the working-copy volume mounts at, and the working
 *     directory
 * @param ownership the mode and project identity stamped on the container at creation
 * @param overrides the image-declared paths to make ephemeral, from {@link
 *     DeclaredVolumeOverrides#resolve}; never null, possibly empty
 */
record ContainerRunSpec(
        String key,
        String image,
        String runtime,
        ResourceLimits limits,
        boolean enforceDiskQuota,
        String workingCopy,
        ObjectOwnership ownership,
        DeclaredVolumeOverrides overrides) {}
