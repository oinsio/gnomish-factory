package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR6, FR9 of add-sandbox-core: the seam methods of {@code ContainerEnvironments}
 * that the assemblies and end-of-task bookkeeping drive — the production
 * {@code forTask} construction, the credential-scrub probe, and the keep
 * semantics of {@code stopKeeping}.
 *
 * <p>New spec file for task 6.1 of split-into-modules: these lines were killed
 * only incidentally, by the composition root's container-mode specs that now
 * live in other modules. Per-module PIT (D6) needs this module's own specs to
 * cover its own classes — same reasoning as {@code ExecCommandSpec} at task 3.1.
 */
class ContainerEnvironmentsSeamSpec extends Specification {

    static final String KEY = 'org-repo-9'

    def docker = new RecordingDockerCli()
    def sandbox = new SandboxProperties('gnomish/img', null, null, null, null, null, false)
    def clock = { -> Instant.now() } as Clock
    def harvester = { String container, String branch -> } as ContainerHarvest
    def sleeper = { Duration d -> } as Sleeper

    private ContainerEnvironments environments(ChildEnvAllowlist allowlist) {
        new ContainerEnvironments(
                docker, KEY, Path.of('/factory/clone'), harvester, sandbox,
                clock, allowlist, sleeper, Path.of('/factory/guard-config'))
    }

    // The app-layer assemblies construct through forTask because DockerCli is package-private
    def "forTask builds the per-task environment seam, never a null"() {
        when: 'the production construction path runs'
        def seam = ContainerEnvironments.forTask(
                KEY, Path.of('/factory/clone'), harvester, sandbox,
                clock, ChildEnvAllowlist.none(), sleeper, Path.of('/factory/guard-config'))

        then: 'the seam is real and carries the round key it was built for'
        seam.baseKey() == KEY
    }

    // FR9: the probe answers what the composed allowlist actually does, never a hardwired boolean
    def "scrubsCredential is true only for a name the allowlist scrubs"() {
        expect: 'a declared credential name is scrubbed'
        environments(ChildEnvAllowlist.of([], ['GNOMISH_PROBE_TOKEN'])).scrubsCredential('GNOMISH_PROBE_TOKEN')

        and: 'a name no allowlist declares passes through, so the probe says not scrubbed'
        !environments(ChildEnvAllowlist.none()).scrubsCredential('GNOMISH_PROBE_TOKEN')
    }

    // FR6: keep semantics — the round container is stopped, volume and network stay for resume
    def "stopKeeping stops exactly the round key's container"() {
        when:
        environments(ChildEnvAllowlist.none()).stopKeeping()

        then: 'the one docker invocation is the stop of this task\'s container'
        docker.runs == [
            DockerCommands.stop(FactoryDockerLabels.containerName(KEY))
        ]
    }
}
