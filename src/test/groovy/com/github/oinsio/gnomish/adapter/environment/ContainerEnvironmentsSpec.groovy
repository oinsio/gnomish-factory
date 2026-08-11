package com.github.oinsio.gnomish.adapter.environment

import com.github.oinsio.gnomish.SandboxProperties
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR3, FR8, D5, D9, D13 of add-sandbox-core: the per-task construction seam for
 * guarded container environments, verified without a daemon — the docker
 * availability probe's decision (ok-exit true, non-zero false, unreachable
 * runtime false, never a silent fallback), the judge-role environment wiring,
 * the round key exposed for bookkeeping, and disposeExisting's full teardown of
 * the round key's objects.
 */
class ContainerEnvironmentsSpec extends Specification {

    static final String KEY = 'org-repo-7'

    def docker = new RecordingDockerCli()
    def sandbox = new SandboxProperties('gnomish/img', null, null, null, null, null, false)
    def clock = { -> Instant.now() } as Clock
    def harvester = { String container, String branch -> } as ContainerHarvest
    def sleeper = { Duration d -> } as Sleeper

    private ContainerEnvironments environments() {
        new ContainerEnvironments(
                docker, KEY, Path.of('/factory/clone'), harvester, sandbox,
                clock, ChildEnvAllowlist.none(), sleeper, Path.of('/factory/guard-config'))
    }

    // D13, G2: the probe answers exactly what the daemon answered — never a hardwired boolean
    def "dockerAvailable is #available when the docker version probe exits #exit"() {
        given: 'a runtime whose version probe exits with the scripted code'
        docker.onRun = { args -> new DockerResult(exit, '', '') }

        expect:
        ContainerEnvironments.dockerAvailable(docker) == available
        docker.runs == [
            [
                'version',
                '--format',
                '{{.Server.Version}}'
            ]
        ]

        where:
        exit | available
        0    | true
        1    | false
    }

    // D13, NFR-R1: an unreachable runtime is the fail-closed false, not an escaping exception
    def "dockerAvailable is false when the docker runtime is unreachable"() {
        given:
        docker.onRun = { args -> throw new DockerUnavailableException('Cannot connect to the Docker daemon', null) }

        expect:
        !ContainerEnvironments.dockerAvailable(docker)
    }

    // FR8, D9: the judge role gets a real self-checked container environment, never a null seam
    def "judgeEnvironment builds a self-checked container environment"() {
        when:
        def judge = environments().judgeEnvironment()

        then: 'a non-null decorator over the container adapter, guard attached'
        judge != null
        judge.passport() == CapabilityPassport.container()
        judge.guard() != null
    }

    // FR3, FR8: the round role gets a real self-checked container environment, never a null seam
    def "roundEnvironment builds a self-checked container environment"() {
        when:
        def round = environments().roundEnvironment()

        then:
        round != null
        round.passport() == CapabilityPassport.container()
        round.guard() != null
    }

    // FR13: the fresh verify-in: fresh-box role gets a real self-checked container environment,
    // never a null seam
    def "verificationEnvironment builds a self-checked container environment"() {
        when:
        def verification = environments().verificationEnvironment()

        then:
        verification != null
        verification.passport() == CapabilityPassport.container()
        verification.guard() != null
    }

    // FR6: the round key is exposed verbatim for keep/dispose bookkeeping
    def "baseKey returns the round environment's sanitized key"() {
        expect:
        environments().baseKey() == KEY
    }

    // FR6, NFR-R2: disposeExisting removes container, guard, volume and network of the round key
    def "disposeExisting tears down every docker object of the round key"() {
        when:
        environments().disposeExisting()

        then:
        docker.runs == [
            DockerCommands.removeContainer('gnomish-box-' + KEY),
            GuardCommands.removeGuard(KEY),
            DockerCommands.removeVolume('gnomish-vol-' + KEY),
            DockerCommands.removeNetwork('gnomish-net-' + KEY),
        ]
    }
}
