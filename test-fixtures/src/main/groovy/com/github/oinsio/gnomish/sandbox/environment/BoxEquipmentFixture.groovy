package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Path
import java.time.Duration
import java.time.InstantSource

/**
 * The installation's {@link BoxEquipment} for specs that build one container box directly (FR13
 * of tighten-spec-contract, design D12): the docker seam and the clock the spec drives, the image
 * and limits it runs, and the installation defaults for everything else — the runtime {@code
 * runc}, no egress allowlist, no disk quota, a fixed guard config root and a sleeper that returns
 * at once. A spec that needs another value of the sandbox config builds the record itself.
 */
final class BoxEquipmentFixture {

    /** The guard config root of every fixture installation; no box built from it renders a guard. */
    static final Path GUARD_CONFIG_ROOT = Path.of('/factory/guard-config')

    private BoxEquipmentFixture() {
    }

    /**
     * @param docker the docker seam the box runs on: a real one, or a recording or scripted fake
     * @param image the box image
     * @param limits the box resource limits
     * @param clock the exec clock the box stamps its handles with
     * @param mode the ownership mode stamped on every object; {@code TRACKED} by default
     */
    static BoxEquipment forBox(
            DockerCli docker,
            String image,
            ResourceLimits limits,
            InstantSource clock,
            OwnershipMode mode = OwnershipMode.TRACKED) {
        def sandbox = new SandboxProperties(image, 'runc', null, limits, null, null, false, null, null, null, null)
        def timing = new BoxTiming(VirtualTimeEquipment.on(clock, { Duration d -> } as Sleeper), DockerCli.DEFAULT_COMMAND_TIMEOUT)
        new BoxEquipment(docker, sandbox, timing, GUARD_CONFIG_ROOT, mode)
    }
}
