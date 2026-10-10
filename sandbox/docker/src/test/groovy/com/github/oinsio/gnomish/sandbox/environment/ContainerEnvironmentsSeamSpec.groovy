package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.DenialRestoration
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR6, FR9 of add-sandbox-core: the seam methods of {@code ContainerEnvironments}
 * that the assemblies and end-of-task bookkeeping drive — the production
 * construction through {@code ContainerEnvironmentFactory.forTask} (FR19 of
 * make-checkpoint-gate-durable), the credential-scrub probe, and the keep
 * semantics of {@code stopKeeping}.
 *
 * <p>New spec file for task 6.1 of split-into-modules: these lines were killed
 * only incidentally, by the composition root's container-mode specs that now
 * live in other modules. Per-module PIT (D6) needs this module's own specs to
 * cover its own classes — same reasoning as {@code ExecCommandSpec} at task 3.1.
 */
class ContainerEnvironmentsSeamSpec extends Specification implements ContainerEnvironmentsFixture {

    static final String KEY = 'org-repo-9'

    private ContainerEnvironments factoryBuilt(OwnershipMode mode, ChildEnvAllowlist allowlist) {
        new ContainerEnvironmentFactory(sandbox, timing, Path.of('/factory/guard-config'), mode).forTask(
                KEY, new BoxGitLink(Path.of('/factory/clone'), harvester), allowlist, 'proj-1',
                { -> DenialRestoration.none() })
    }

    // FR19 of make-checkpoint-gate-durable: the app-layer assemblies construct through the
    // factory because DockerCli is package-private; the installation half (mode) comes from the
    // factory's construction and the per-task half (key) from forTask
    def "the factory builds the per-task seam for the key and mode it was given"() {
        when: 'the production construction path runs'
        def seam = factoryBuilt(mode, ChildEnvAllowlist.none())

        then: 'the seam is real, carries the round key it was built for and the factory\'s mode'
        seam.baseKey() == KEY
        seam.ownershipMode() == mode

        where:
        mode << [
            OwnershipMode.TRACKED,
            OwnershipMode.MANUAL
        ]
    }

    // FR18, FR22 of supervise-daemon-loops-and-embed-dashboard (design D22): the run that owns
    // the seam reads its time equipment from the box timing the factory was built with, so a
    // run and its boxes measure on one time source
    def "a factory-built seam carries the installation's box timing"() {
        expect:
        factoryBuilt(OwnershipMode.TRACKED, ChildEnvAllowlist.none()).timing().is(timing)
    }

    // FR9 of add-sandbox-core, FR19 of make-checkpoint-gate-durable: the per-task allowlist
    // handed to forTask is the one the built seam scrubs with
    def "a factory-built seam scrubs exactly the credentials of the allowlist it was given"() {
        expect:
        factoryBuilt(OwnershipMode.TRACKED, ChildEnvAllowlist.of([], ['GNOMISH_PROBE_TOKEN']))
        .scrubsCredential('GNOMISH_PROBE_TOKEN')

        and:
        !factoryBuilt(OwnershipMode.TRACKED, ChildEnvAllowlist.none()).scrubsCredential('GNOMISH_PROBE_TOKEN')
    }

    // FR2 of add-serve-sandbox-lifecycle: the seam reports the mode it was built with, not a
    // default — this is what lets a composition root's wiring be asserted without a live daemon
    // (`ManualRunRunnerContainerOwnershipSpec` in bootstrap reads exactly this).
    def "ownershipMode reports the mode the seam was constructed with"() {
        expect:
        environments(KEY, ChildEnvAllowlist.none()).ownershipMode() == OwnershipMode.TRACKED

        and:
        new ContainerEnvironments(docker, KEY, new ContainerEnvironmentBuilder(
                        docker, new BoxGitLink(Path.of('/factory/clone'), harvester), sandbox,
                        timing, ChildEnvAllowlist.none(),
                        Path.of('/factory/guard-config'), new ObjectOwnership(OwnershipMode.MANUAL, 'proj-1')),
                { -> DenialRestoration.none() }, timing)
                .ownershipMode() == OwnershipMode.MANUAL
    }

    // FR9: the probe answers what the composed allowlist actually does, never a hardwired boolean
    def "scrubsCredential is true only for a name the allowlist scrubs"() {
        expect: 'a declared credential name is scrubbed'
        environments(KEY, ChildEnvAllowlist.of([], ['GNOMISH_PROBE_TOKEN'])).scrubsCredential('GNOMISH_PROBE_TOKEN')

        and: 'a name no allowlist declares passes through, so the probe says not scrubbed'
        !environments(KEY, ChildEnvAllowlist.none()).scrubsCredential('GNOMISH_PROBE_TOKEN')
    }

    // FR6: keep semantics — the round container is stopped, volume and network stay for resume
    def "stopKeeping stops exactly the round key's container"() {
        when:
        environments(KEY, ChildEnvAllowlist.none()).stopKeeping()

        then: 'the one docker invocation is the stop of this task\'s container'
        docker.runs == [
            DockerCommands.stop(FactoryDockerLabels.containerName(KEY))
        ]
    }
}
