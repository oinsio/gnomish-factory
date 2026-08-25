package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

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
    Clock clock = { -> Instant.now() } as Clock
    ContainerHarvest harvester = { String container, String branch -> } as ContainerHarvest
    Sleeper sleeper = { Duration d -> } as Sleeper

    ContainerEnvironments environments(String key, ChildEnvAllowlist allowlist = ChildEnvAllowlist.none()) {
        new ContainerEnvironments(
                docker, key, Path.of('/factory/clone'), harvester, sandbox,
                clock, allowlist, sleeper, Path.of('/factory/guard-config'),
                OwnershipMode.TRACKED, 'proj-1')
    }
}
