package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.DenialRestoration
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Path
import java.time.Duration
import java.time.InstantSource
import java.util.function.Supplier

/**
 * Shared daemon-free construction seam for {@link ContainerEnvironments} specs
 * (FR3, FR6, FR8, FR9 of add-sandbox-core): the recording docker fake, the
 * minimal sandbox properties, and the trivial clock/harvester/sleeper stand-ins
 * every {@code ContainerEnvironments} spec needs to build the seam without a
 * live daemon.
 */
trait ContainerEnvironmentsFixture {

    RecordingDockerCli docker = new RecordingDockerCli()
    SandboxProperties sandbox = new SandboxProperties(
    'gnomish/img', null, null, null, null, null, false, null, null, null, null)
    InstantSource clock = new VirtualClock()
    ContainerHarvest harvester = { String container, String branch -> } as ContainerHarvest
    Sleeper sleeper = { Duration d -> } as Sleeper
    // The docker-command bound is the production default: no scripted command here ever waits on it.
    BoxTiming timing = new BoxTiming(VirtualTimeEquipment.on(clock, sleeper), DockerCli.DEFAULT_COMMAND_TIMEOUT)

    ContainerEnvironments environments(
            String key,
            ChildEnvAllowlist allowlist = ChildEnvAllowlist.none(),
            Supplier<DenialRestoration> restoration = {
                -> DenialRestoration.none()
            }) {
        new ContainerEnvironments(docker, key, new ContainerEnvironmentBuilder(
                        docker, new BoxGitLink(Path.of('/factory/clone'), harvester), sandbox,
                        timing, allowlist, Path.of('/factory/guard-config'),
                        new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1')), restoration, timing)
    }
}
