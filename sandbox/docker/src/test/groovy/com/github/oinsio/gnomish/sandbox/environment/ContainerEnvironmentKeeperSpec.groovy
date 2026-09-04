package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
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
        def kept = keeper.stopKeeping('k1')

        then: 'only a stop is issued — no volume or network removal'
        docker.runs == [
            DockerCommands.stop('gnomish-box-k1')
        ]

        and: 'and the caller is told the runtime accepted it (FR3 of polish-sandbox-forensics)'
        kept
    }

    def "stopKeeping is best-effort on a runtime outage"() {
        given:
        docker.onRun = { List<String> args ->
            throw new DockerUnavailableException('down', null)
        } as Closure<DockerResult>

        when:
        def kept = keeper.stopKeeping('k1')

        then:
        noExceptionThrown()

        and: 'but the caller is told nothing was kept — a stop that never happened is not a keep'
        !kept
    }

    // FR3 of polish-sandbox-forensics: a daemon that answers "no such container" is not an
    // outage and does not throw, yet it is just as much a stop that did not take.
    def "FR3: a stop the daemon refuses reports as not kept, without throwing"() {
        given:
        docker.onRun = { args ->
            new DockerResult(1, '', 'No such container: gnomish-box-k1')
        }

        when:
        def kept = keeper.stopKeeping('k1')

        then:
        noExceptionThrown()
        !kept
    }

    // FR2, NFR-O1, UX1, M2 of polish-sandbox-forensics: this line, not the caller's, is where the
    // operator reads what survived a park, an abort or a rejected self-check — with a name that
    // goes straight into `docker logs`.
    def "FR2: a successful keep announces the kept container by name"() {
        given:
        def capture = LogCaptureSupport.attach(ContainerEnvironmentKeeper)

        when:
        keeper.stopKeeping('k1')

        then:
        def notice = capture.list.find { it.level == Level.INFO }
        notice != null
        notice.formattedMessage.contains('gnomish-box-k1')

        cleanup:
        capture.detach()
    }

    def "the best-effort failure path still swallows, and announces nothing kept"() {
        given:
        docker.onRun = { List<String> args ->
            throw new DockerUnavailableException('down', null)
        } as Closure<DockerResult>
        def capture = LogCaptureSupport.attach(ContainerEnvironmentKeeper, Level.DEBUG)

        when:
        keeper.stopKeeping('k1')

        then: 'no keep was announced — nothing was kept'
        noExceptionThrown()
        capture.list.findAll { it.level == Level.INFO } == []

        and: 'but the swallowed failure still left a trace naming the container'
        def trace = capture.list.find { it.level == Level.DEBUG }
        trace != null
        trace.formattedMessage.contains('gnomish-box-k1')

        cleanup:
        capture.detach()
    }
}
