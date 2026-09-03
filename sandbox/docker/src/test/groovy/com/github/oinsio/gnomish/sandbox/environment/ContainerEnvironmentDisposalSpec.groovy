package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
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

    // FR2 of harden-logging-observability: disposal closes the container environment's lifecycle,
    // so it gets the same INFO anchor its creation and reattachment do.
    def "FR2: a disposal logs one INFO anchor naming the environment"() {
        given:
        def capture = LogCaptureSupport.attach(ContainerEnvironmentDisposal)

        when:
        disposal.dispose('k9')

        then:
        capture.list.size() == 1
        capture.list[0].level == Level.INFO
        capture.list[0].formattedMessage == 'container environment k9 disposed'

        cleanup:
        capture.detach()
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

    // FR5 of harden-logging-observability: best-effort is not the same as silent. A swallowed
    // step names which step it was and which environment it was for — "a dispose step failed" is
    // unactionable when the sweep is disposing a dozen environments at once — and carries the
    // throwable so the cause chain survives.
    def "FR5: a swallowed dispose step names the step, the environment and its cause"() {
        given:
        def capture = LogCaptureSupport.attach(ContainerEnvironmentDisposal, Level.DEBUG)
        docker.onRun = { args ->
            if (args == DockerCommands.removeVolume('gnomish-vol-k9')) {
                throw new DockerUnavailableException('down', null)
            }
            new DockerResult(0, '', '')
        }

        when:
        disposal.dispose('k9')

        then:
        def failures = capture.list.findAll { it.level == Level.DEBUG }
        failures.size() == 1
        failures[0].formattedMessage.contains("'remove volume'")
        failures[0].formattedMessage.contains('environment k9')
        failures[0].throwableProxy != null

        cleanup:
        capture.detach()
    }
}
