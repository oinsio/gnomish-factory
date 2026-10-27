package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.DoctorCommandFixture.check

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.project.ProjectResolution
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification

/**
 * FR1, FR3, FR7 of add-doctor-command (design D3): the command runs every check in the order of
 * {@link DoctorCheckId}, whatever order the beans arrive in, over one context; a check that throws
 * is one failed result and the next check still runs; the online check runs only under {@code
 * --online}. The rendering and the exit code are {@code DoctorCommandOutputSpec}'s.
 */
class DoctorCommandSpec extends Specification {

    static final String LOG_FILE_FIX = "the full error is in ${DoctorCommandFixture.LOG_FILE}; " +
    'fix what it names, then run gnomish doctor again'

    ConsoleIO console = Mock()

    def "FR1: every check runs in report order, and a throwing check is one failure that stops nothing"() {
        given: 'three checks handed in reverse order, the middle one throwing'
        def legacy = check(DoctorCheckId.LEGACY_LAYOUT) {
            [
                new DoctorVerdict(DoctorCheckId.LEGACY_LAYOUT, DoctorStatus.OK, 'clean', null)
            ]
        }
        def pipeline = check(DoctorCheckId.PIPELINE) {
            throw new IllegalStateException('boom')
        }
        def git = check(DoctorCheckId.GIT_FLOOR) {
            [
                new DoctorVerdict(DoctorCheckId.GIT_FLOOR, DoctorStatus.FAIL, 'old', 'upgrade')
            ]
        }
        def command = DoctorCommandFixture.command([legacy, pipeline, git], console)

        expect:
        command.verdicts(false) == [
            new DoctorVerdict(DoctorCheckId.GIT_FLOOR, DoctorStatus.FAIL, 'old', 'upgrade'),
            new DoctorVerdict(DoctorCheckId.PIPELINE, DoctorStatus.FAIL,
            'the check could not run: java.lang.IllegalStateException: boom', LOG_FILE_FIX),
            new DoctorVerdict(DoctorCheckId.LEGACY_LAYOUT, DoctorStatus.OK, 'clean', null),
        ]
    }

    // UX1, NFR-O1: the line names the fault and sends the operator to the log file holding it whole
    def "UX1: a throwing check's fail line renders its message safely and names the log file (#fault)"() {
        given:
        def thrower = check(DoctorCheckId.PIPELINE) {
            throw failure
        }
        def logs = LogCaptureSupport.attach(DoctorCommand, Level.DEBUG)

        when:
        def verdicts = DoctorCommandFixture.command([thrower], console).verdicts(false)

        then: 'one fail line with a fix, the message rendered for the console'
        verdicts == [
            new DoctorVerdict(DoctorCheckId.PIPELINE, DoctorStatus.FAIL, detail, LOG_FILE_FIX)
        ]

        and: 'the throwable is recorded once, whole, at INFO: WARN would reach stdout (D5)'
        logs.list.size() == 1
        logs.list[0].level == Level.INFO
        logs.list[0].formattedMessage == 'doctor check pipeline could not run'
        logs.list[0].throwableProxy.className == failure.class.name
        logs.list[0].throwableProxy.cause.message == 'root'

        cleanup:
        logs.detach()

        where:
        fault | failure || detail
        'a message' | new IllegalStateException('boom', new IOException('root')) || 'the check could not run: java.lang.IllegalStateException: boom'
        'no message' | new IllegalStateException(null, new IOException('root')) || 'the check could not run: java.lang.IllegalStateException'
        'a hostile one' | new IllegalArgumentException('x\u001b[2Jy', new IOException('root')) || 'the check could not run: java.lang.IllegalArgumentException: x^[[2Jy'
    }

    def "FR1: each check is handed the one context the survey read"() {
        given:
        def seen = []
        def first = check(DoctorCheckId.FACTORY_VERSION) { DoctorContext c ->
            seen << c; []
        }
        def second = check(DoctorCheckId.HOOKS_PATH) { DoctorContext c ->
            seen << c; []
        }

        when:
        DoctorCommandFixture.command([second, first], console).verdicts(false)

        then:
        seen.size() == 2
        seen[0].is(seen[1])
        seen[0].project() instanceof ProjectResolution.Unregistered
    }

    // FR3, NG5: the online check is not run without --online, so it reaches nothing and has no line
    def "FR3: the tracker read probe runs only under --online (#arguments)"() {
        given:
        def runs = 0
        def probe = check(DoctorCheckId.TRACKER_PROBE) {
            runs++
            [
                new DoctorVerdict(DoctorCheckId.TRACKER_PROBE, DoctorStatus.OK, 'read', null)
            ]
        }
        def git = check(DoctorCheckId.GIT_FLOOR) {
            [
                new DoctorVerdict(DoctorCheckId.GIT_FLOOR, DoctorStatus.OK, 'fine', null)
            ]
        }
        def out = new StringBuilder()
        console.print(_ as String) >> { String text -> out << text }
        console.printMachine(_ as String) >> { String text -> out << text }

        when:
        DoctorCommandFixture.command([probe, git], console).run(new DefaultApplicationArguments(arguments as String[]))

        then:
        runs == expectedRuns
        out.toString().contains('fine')
        out.toString().contains('read') == (expectedRuns == 1)

        where:
        arguments || expectedRuns
        ['doctor'] || 0
        ['doctor', '--online'] || 1
        ['doctor', '--json'] || 0
    }
}
