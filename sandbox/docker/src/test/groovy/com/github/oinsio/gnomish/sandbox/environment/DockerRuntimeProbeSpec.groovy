package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import spock.lang.Specification

/**
 * D13, G2, NFR-R1 of add-sandbox-core: the container-mode prerequisite probe,
 * daemon-free — the answer is exactly what the runtime answered (never a
 * hardwired boolean), and an unreachable runtime is the fail-closed false rather
 * than an escaping exception. Split out of {@code ContainerEnvironmentsSpec}
 * with the probe itself.
 */
class DockerRuntimeProbeSpec extends Specification {

    def docker = new RecordingDockerCli()

    def "dockerAvailable is #available when the docker version probe exits #exit"() {
        given: 'a runtime whose version probe exits with the scripted code'
        docker.onRun = { args -> new DockerResult(exit, '', '') }

        expect:
        DockerRuntimeProbe.dockerAvailable(docker) == available
        docker.runs == [
            [
                'version',
                '--format',
                '{{.Server.Version}}'
            ]
        ]

        where:
        exit | available
        0 | true
        1 | false
    }

    def "dockerAvailable is false when the docker runtime is unreachable"() {
        given:
        docker.onRun = { List<String> args ->
            throw new DockerUnavailableException('Cannot connect to the Docker daemon', null)
        } as Closure<DockerResult>

        expect:
        !DockerRuntimeProbe.dockerAvailable(docker)
    }

    // FR5 of harden-logging-observability: the caller turns a false into a refusal naming the MODE,
    // so this is the only line saying the daemon was asked and did not answer. INFO: "no Docker
    // here" is a legitimate configuration, not something the operator must act on.
    def "FR5: a probe that answers no leaves an INFO trace (#label)"() {
        given:
        docker.onRun = answer
        def logs = LogCaptureSupport.attach(DockerRuntimeProbe)

        when:
        def available = DockerRuntimeProbe.dockerAvailable(docker)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        !available

        and:
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('container mode is unavailable')
        (events[0].throwableProxy != null) == carriesCause

        where:
        label | carriesCause | answer
        'non-zero' | false | { List<String> args ->
            new DockerResult(1, '', '')
        }
        'unreachable' | true | { List<String> args ->
            throw new DockerUnavailableException('Cannot connect to the Docker daemon', null)
        } as Closure<DockerResult>
    }

    // FR5: a runtime that answers ok says nothing — a healthy start produces no output.
    def "FR5: a probe that answers ok is silent"() {
        given:
        docker.onRun = { List<String> args -> new DockerResult(0, '', '') }
        def logs = LogCaptureSupport.attach(DockerRuntimeProbe)

        when:
        def available = DockerRuntimeProbe.dockerAvailable(docker)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        available
        events.isEmpty()
    }
}
