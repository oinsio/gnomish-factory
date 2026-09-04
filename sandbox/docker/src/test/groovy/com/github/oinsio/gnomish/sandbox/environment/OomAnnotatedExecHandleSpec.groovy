package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR1, NFR-R1, NFR-O1, UX2 of polish-sandbox-forensics: an in-box process that exits 137 is
 * annotated with the container's own OOM state, so an operator raises the sandbox memory limit
 * instead of bisecting a build. The annotation is advisory in every direction — the exit code and
 * the wait outcome are untouched, and an unreadable runtime state degrades to no claim.
 */
class OomAnnotatedExecHandleSpec extends Specification {

    static final String CONTAINER = 'gnomish-box-org-repo-7'
    static final List<String> INSPECT = DockerCommands.inspectContainerState(CONTAINER)

    def docker = new RecordingDockerCli()
    def delegate = Mock(ExecHandle)

    private OomAnnotatedExecHandle handle() {
        new OomAnnotatedExecHandle(delegate, docker, CONTAINER)
    }

    private static DockerResult state(String oomKilled) {
        new DockerResult(0, "false 2026-08-07T10:00:00Z ${oomKilled}\n", '')
    }

    // FR1, UX2, M1: the OOM-killed exec is the whole point — the annotation names the container
    // and the exit code at the moment the exit code is surfaced.
    def "FR1: an exit 137 in an OOM-killed container is reported as a likely container OOM"() {
        given:
        docker.onRun = { List<String> args -> state('true') }
        def capture = LogCaptureSupport.attach(OomAnnotatedExecHandle)

        when:
        def exitCode = handle().waitForExit()

        then: 'the exit code the caller classifies on is exactly the delegate\'s'
        1 * delegate.waitForExit() >> 137
        exitCode == 137

        and: 'and the container state was read through the extended inspect'
        docker.runs == [INSPECT]

        and: 'NFR-O1: the annotation is at the failure site, naming the ready-to-paste container'
        def warning = capture.list.find {
            it.formattedMessage.startsWith(OperatorEvent.CONTAINER_EXEC_LIKELY_OOM_KILLED.head())
        }
        warning != null
        warning.level == Level.WARN
        warning.formattedMessage.contains(CONTAINER)
        warning.formattedMessage.contains('137')
        warning.formattedMessage.contains('likely container OOM')

        cleanup:
        capture.detach()
    }

    def "FR1: a plain kill is not blamed on memory"() {
        given:
        docker.onRun = { List<String> args -> state('false') }
        def capture = LogCaptureSupport.attach(OomAnnotatedExecHandle)

        when:
        def exitCode = handle().waitForExit()

        then:
        1 * delegate.waitForExit() >> 137
        exitCode == 137
        docker.runs == [INSPECT]
        capture.list.findAll { it.level == Level.WARN } == []

        cleanup:
        capture.detach()
    }

    // NFR-R1: forensics never invent a failure of their own — both shapes of an unreadable state
    def "NFR-R1: an unreadable runtime state degrades to no annotation and no new failure"() {
        given:
        docker.onRun = failing
        def capture = LogCaptureSupport.attach(OomAnnotatedExecHandle)

        when:
        def exitCode = handle().waitForExit()

        then:
        1 * delegate.waitForExit() >> 137
        noExceptionThrown()
        exitCode == 137
        docker.runs == [INSPECT]
        capture.list.findAll { it.level == Level.WARN } == []

        cleanup:
        capture.detach()

        where:
        failing << ([
            { List<String> args -> new DockerResult(1, '', 'No such object') },
            { List<String> args ->
                throw new DockerUnavailableException('daemon down', null)
            }
        ] as List<Closure<DockerResult>>)
    }

    def "NFR-R1: any other exit code costs no inspect at all"() {
        given:
        def capture = LogCaptureSupport.attach(OomAnnotatedExecHandle)

        when:
        def exitCode = handle().waitForExit()

        then:
        1 * delegate.waitForExit() >> exit
        exitCode == exit
        docker.runs == []
        capture.list.findAll { it.level == Level.WARN } == []

        cleanup:
        capture.detach()

        where:
        exit << [0, 1, 136, 138, -1]
    }

    // NFR-R1: the wrapper is a pass-through everywhere else — the Wait outcome carries no exit
    // code (design D1), so there is nothing to annotate on the timed wait.
    def "NFR-R1: the timed wait, the output stream and the start instant pass through untouched"() {
        given:
        def stream = new ByteArrayInputStream('real-bytes'.bytes)
        def startedAt = Instant.parse('2026-08-07T09:59:00Z')
        def outcome = new ExecHandle.Wait.Exited(Duration.ofSeconds(3))
        def clock = { -> Instant.parse('2026-08-07T10:00:00Z') } as Clock
        def subject = handle()

        when:
        def seenWait = subject.waitForExitOrTimeout(Duration.ofMinutes(5), clock)
        def seenOutput = subject.output()
        def seenStart = subject.startedAt()

        then:
        1 * delegate.waitForExitOrTimeout(Duration.ofMinutes(5), clock) >> outcome
        1 * delegate.output() >> stream
        1 * delegate.startedAt() >> startedAt
        seenWait.is(outcome)
        seenOutput.is(stream)
        seenStart == startedAt

        and: 'a timed wait never reaches the runtime for state it could not annotate anyway'
        docker.runs == []
    }
}
