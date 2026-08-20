package com.github.oinsio.gnomish.sandbox.environment

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
}
