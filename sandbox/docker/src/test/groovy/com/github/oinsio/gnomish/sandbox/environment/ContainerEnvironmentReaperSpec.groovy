package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR11, NFR-R2 of add-sandbox-core (factory-serve delta): keep semantics stop an
 * ended task's container while retaining its volume and network; the aged-reap
 * disposes stopped containers older than the threshold by runtime finished-at,
 * skipping held tasks, running containers, and freshly-finished ones, and never
 * throwing on a runtime outage.
 */
class ContainerEnvironmentReaperSpec extends Specification {

    static final Instant NOW = Instant.parse('2026-08-07T12:00:00Z')
    static final Duration ONE_HOUR = Duration.ofHours(1)

    def docker = new RecordingDockerCli()
    def disposal = Mock(TaskEnvironmentDisposal)
    def reaper = new ContainerEnvironmentReaper(docker, disposal)

    def "FR11: stopKeeping stops the container, leaving volume and network in place"() {
        when:
        reaper.stopKeeping('k1')

        then: 'only a stop is issued — no volume or network removal'
        docker.runs == [
            DockerCommands.stop('gnomish-box-k1')
        ]
    }

    def "NFR-R2: stopKeeping is best-effort on a runtime outage"() {
        given:
        docker.onRun = { args ->
            throw new DockerUnavailableException('down', null)
        }

        when:
        reaper.stopKeeping('k1')

        then:
        noExceptionThrown()
    }

    def "FR11: an aged stopped container is disposed; held, running and fresh ones are kept"() {
        given:
        docker.onRun = { List<String> args ->
            if (args == DockerCommands.listContainerNames()) {
                return listing('gnomish-box-aged\ngnomish-box-held\ngnomish-box-running\ngnomish-box-fresh\n')
            }
            if (args == DockerCommands.inspectContainerState('gnomish-box-aged')) {
                return listing('false 2026-08-07T10:00:00Z')
            }
            if (args == DockerCommands.inspectContainerState('gnomish-box-held')) {
                return listing('false 2026-08-07T10:00:00Z')
            }
            if (args == DockerCommands.inspectContainerState('gnomish-box-running')) {
                return listing('true 0001-01-01T00:00:00Z')
            }
            if (args == DockerCommands.inspectContainerState('gnomish-box-fresh')) {
                return listing('false 2026-08-07T11:59:00Z')
            }
            listing('')
        }

        when:
        reaper.reapAged(['held'] as Set, ONE_HOUR, NOW)

        then: 'only the aged, unheld, stopped container is disposed'
        1 * disposal.dispose('aged')
        0 * disposal.dispose('held')
        0 * disposal.dispose('running')
        0 * disposal.dispose('fresh')
    }

    def "NFR-R2: a runtime outage skips the whole reap pass without throwing"() {
        given:
        docker.onRun = { args ->
            throw new DockerUnavailableException('down', null)
        }

        when:
        reaper.reapAged([] as Set, ONE_HOUR, NOW)

        then:
        noExceptionThrown()
        0 * disposal.dispose(_)
    }

    def "an unreadable container state is skipped, never disposed"() {
        given:
        docker.onRun = { List<String> args ->
            if (args == DockerCommands.listContainerNames()) {
                return listing('gnomish-box-gone\n')
            }
            new DockerResult(1, '', 'No such object')
        }

        when:
        reaper.reapAged([] as Set, ONE_HOUR, NOW)

        then:
        0 * disposal.dispose(_)
    }

    private static DockerResult listing(String stdout) {
        new DockerResult(0, stdout, '')
    }
}
