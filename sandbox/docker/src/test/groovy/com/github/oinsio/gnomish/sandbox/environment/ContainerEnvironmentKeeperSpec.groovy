package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * FR11 of add-sandbox-core: keep semantics stop an ended task's container while retaining its
 * volume and network. The bulk aged-reap duty this class formerly also carried moved to {@link
 * SandboxLifecycleSweep} (task 3.4 of add-serve-sandbox-lifecycle); see {@code
 * SandboxLifecycleSweepSpec} and the {@code SandboxLifecycleDecisionSpecBase} family for that
 * coverage.
 */
class ContainerEnvironmentKeeperSpec extends Specification {

    def docker = new RecordingDockerCli()
    def keeper = new ContainerEnvironmentKeeper(docker)

    def "FR11: stopKeeping stops the container, leaving volume and network in place"() {
        when:
        keeper.stopKeeping('k1')

        then: 'only a stop is issued — no volume or network removal'
        docker.runs == [
            DockerCommands.stop('gnomish-box-k1')
        ]
    }

    def "stopKeeping is best-effort on a runtime outage"() {
        given:
        docker.onRun = { args ->
            throw new DockerUnavailableException('down', null)
        }

        when:
        keeper.stopKeeping('k1')

        then:
        noExceptionThrown()
    }
}
