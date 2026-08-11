package com.github.oinsio.gnomish.adapter.environment

import spock.lang.Specification

/**
 * FR11, NFR-R2 of add-sandbox-core: the startup sweep removes every factory
 * container, volume, and network whose name belongs to no live task, keeps the
 * ones a currently-held task owns, and does nothing (never throws) when the
 * runtime is unavailable.
 */
class ContainerOrphanSweeperSpec extends Specification {

    def docker = new RecordingDockerCli()
    def sweeper = new ContainerOrphanSweeper(docker)

    private void listing(Map<List<String>, String> stdoutByArgv) {
        docker.onRun = { List<String> args -> new DockerResult(0, stdoutByArgv.getOrDefault(args, ''), '') }
    }

    def "FR11: orphaned factory objects are removed, live-task objects are kept"() {
        given:
        listing([
            (DockerCommands.listContainerNames()): 'gnomish-box-k1\ngnomish-guard-k1\ngnomish-box-dead\ngnomish-guard-dead\n',
            (DockerCommands.listVolumeNames()): 'gnomish-vol-k1\ngnomish-vol-dead\n',
            (DockerCommands.listNetworkNames()): 'gnomish-net-k1\ngnomish-net-dead\n',
        ])

        when:
        sweeper.sweep(['k1'] as Set)

        then: 'the non-live names are removed across all three object classes, the egress guard included (FR7)'
        docker.runs.contains(DockerCommands.removeContainer('gnomish-box-dead'))
        docker.runs.contains(DockerCommands.removeContainer('gnomish-guard-dead'))
        docker.runs.contains(DockerCommands.removeVolume('gnomish-vol-dead'))
        docker.runs.contains(DockerCommands.removeNetwork('gnomish-net-dead'))

        and: 'the live task objects — its box, guard, volume, and network — are never removed'
        !docker.runs.contains(DockerCommands.removeContainer('gnomish-box-k1'))
        !docker.runs.contains(DockerCommands.removeContainer('gnomish-guard-k1'))
        !docker.runs.contains(DockerCommands.removeVolume('gnomish-vol-k1'))
        !docker.runs.contains(DockerCommands.removeNetwork('gnomish-net-k1'))
    }

    def "FR11: with no live tasks every factory object is an orphan"() {
        given:
        listing([(DockerCommands.listContainerNames()): 'gnomish-box-k1\n'])

        when:
        sweeper.sweep([] as Set)

        then:
        docker.runs.contains(DockerCommands.removeContainer('gnomish-box-k1'))
    }

    def "NFR-R1: an unavailable runtime skips the sweep without throwing"() {
        given:
        docker.onRun = { List<String> args ->
            if (args == DockerCommands.listContainerNames()) {
                throw new DockerUnavailableException('down', null)
            }
            new DockerResult(0, '', '')
        }

        when:
        sweeper.sweep([] as Set)

        then: 'the outage aborts the sweep before the later lists, and never propagates'
        noExceptionThrown()
        !docker.runs.contains(DockerCommands.listVolumeNames())
    }
}
