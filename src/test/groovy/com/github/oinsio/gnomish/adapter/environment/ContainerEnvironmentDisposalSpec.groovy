package com.github.oinsio.gnomish.adapter.environment

import spock.lang.Specification

/**
 * FR11, NFR-R2 of add-sandbox-core (factory-serve delta): the container disposal
 * seam removes a task's container, volume, and network by key, and is
 * best-effort — a failing or unavailable-runtime step never stops the others or
 * throws, so the serve cleaner can dispose an aged environment without special
 * error handling.
 */
class ContainerEnvironmentDisposalSpec extends Specification {

    def docker = new RecordingDockerCli()
    def disposal = new ContainerEnvironmentDisposal(docker)

    def "FR11: dispose removes the container, guard, volume and network for the key"() {
        when:
        disposal.dispose('k9')

        then: 'both containers go before the network — a network with live endpoints cannot be removed (FR7)'
        docker.runs == [
            DockerCommands.removeContainer('gnomish-box-k9'),
            GuardCommands.removeGuard('k9'),
            DockerCommands.removeVolume('gnomish-vol-k9'),
            DockerCommands.removeNetwork('gnomish-net-k9'),
        ]
    }

    def "NFR-R2: a failing step never stops the others or throws"() {
        given:
        docker.onRun = { args ->
            if (args == DockerCommands.removeContainer('gnomish-box-k9')) {
                throw new DockerUnavailableException('down', null)
            }
            new DockerResult(0, '', '')
        }

        when:
        disposal.dispose('k9')

        then:
        noExceptionThrown()
        docker.runs.contains(DockerCommands.removeVolume('gnomish-vol-k9'))
        docker.runs.contains(DockerCommands.removeNetwork('gnomish-net-k9'))
    }
}
