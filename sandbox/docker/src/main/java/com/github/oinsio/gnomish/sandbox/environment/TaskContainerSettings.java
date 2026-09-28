package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.ResourceLimits;
import org.jspecify.annotations.Nullable;

/**
 * The operator configuration one task container is created with (design D11 of
 * add-parameter-count-gate): the image, the runtime, the resource bounds and the disk-quota
 * opt-in, read off {@code SandboxProperties} once by {@link ContainerEnvironmentBuilder} and
 * carried whole into {@link ContainerTaskExecutionEnvironment} and {@link ContainerMaterializer}.
 *
 * <p>The image requirement lives in the canonical constructor: an unset {@code
 * factory.sandbox.image} is refused where the value is built, so a blank image cannot become a
 * settings value at all (FR3 of add-sandbox-core, formerly {@code requireImage} in the
 * environment's constructor). The parameter is declared nullable because the operator property
 * is; the component itself is never null.
 *
 * <p>Implements FR6 of add-parameter-count-gate.
 *
 * @param image the operator-configured {@code factory.sandbox.image}; never blank
 * @param runtime the configured container runtime ({@code factory.sandbox.runtime})
 * @param limits the CPU/memory/PID/disk bounds to apply (FR10 of add-sandbox-core)
 * @param enforceDiskQuota whether to add {@code --storage-opt size=} — opt-in, since it needs a
 *     quota-capable storage driver most daemons lack (documented in operator docs)
 */
record TaskContainerSettings(String image, String runtime, ResourceLimits limits, boolean enforceDiskQuota) {

    TaskContainerSettings(@Nullable String image, String runtime, ResourceLimits limits, boolean enforceDiskQuota) {
        this.image = requireImage(image);
        this.runtime = runtime;
        this.limits = limits;
        this.enforceDiskQuota = enforceDiskQuota;
    }

    private static String requireImage(@Nullable String image) {
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("factory.sandbox.image must be set to bind the container adapter (FR3)");
        }
        return image;
    }
}
