package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.status.ReportPlane
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification

/**
 * FR1, FR2, FR8 of make-run-headless: the one render of a stopped task — the escalation report,
 * the checkpoint sentence and the return-path line — plus the per-kind escalation render moved
 * here from the retired resume dialog (FR9, D8 of add-manual-run; D6 of type-untrusted-text).
 */
class TerminalOutcomeRenderSpec extends Specification {

    private static final TerminalOutcomeRender.ReturnPath RETURN_PATH =
    new TerminalOutcomeRender.ReturnPath(Path.of('/work/clone'), 'manual-1')

    private static List<EscalationReport> allReports() {
        [
            new EscalationReport.AttemptsExhausted(3),
            new EscalationReport.DecisionNeeded(UntrustedText.agent('proceed?'), [
                UntrustedText.agent('yes'),
                UntrustedText.agent('no')
            ]),
            new EscalationReport.CannotVerify(new CheckRef(0, UntrustedText.manifest('command:./gradlew test')),
            UntrustedText.subprocess('timeout'), UntrustedText.subprocess('trace')),
            new EscalationReport.PipelineMismatch(UntrustedText.branchDocument('stale-stage')),
            new EscalationReport.CannotExecute(UntrustedText.subprocess('agent crashed'), []),
        ]
    }

    def "FR1: an escalation stop is the console report followed by the return path with an optional decision"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)

        expect:
        TerminalOutcomeRender.escalated(report, RETURN_PATH) ==
                TerminalOutcomeRender.renderEscalation(report, ReportPlane.CONSOLE) + '\n' +
                'To continue: gnomish run --dir=/work/clone --resume=manual-1 [--decision="..."]'
    }

    def "FR2: a checkpoint stop is the checkpoint sentence followed by the return path without a decision"() {
        expect:
        TerminalOutcomeRender.paused('build', RETURN_PATH) ==
                "Stage 'build' passed. Manual checkpoint reached.\n" +
                'To continue: gnomish run --dir=/work/clone --resume=manual-1'
    }

    def "in-place stops carry no return-path line — there is no branch to resume from"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)

        expect:
        TerminalOutcomeRender.escalated(report, null) == TerminalOutcomeRender.renderEscalation(report, ReportPlane.CONSOLE)
        TerminalOutcomeRender.paused('build', null) == "Stage 'build' passed. Manual checkpoint reached."
    }

    def "FR8: the checkpoint sentence names the passed stage"() {
        expect:
        TerminalOutcomeRender.checkpointLine('deploy') == "Stage 'deploy' passed. Manual checkpoint reached."
    }

    def "the return path quotes a --dir a shell would split or expand: #dir"() {
        expect:
        new TerminalOutcomeRender.ReturnPath(Path.of(dir), 'T-1').line(false) ==
                "To continue: gnomish run --dir=${shown} --resume=T-1"

        where:
        dir | shown
        '/plain/a_b-c.d/x+y=z,@%' | '/plain/a_b-c.d/x+y=z,@%'
        '/with space/clone' | "'/with space/clone'"
        "/it's/clone" | "'/it'\\''s/clone'"
        '/$HOME/clone' | "'/\$HOME/clone'"
    }

    // Task 4.5 of make-run-headless: the line is a command, so it is read back the way an operator
    // runs it — split by a real POSIX shell, then parsed by the CLI's own option parser.
    def "FR1, UX1: the return path, split by a POSIX shell, parses to its --dir and --resume values: #dir"() {
        given:
        def line = new TerminalOutcomeRender.ReturnPath(Path.of(dir), 'T-1').line(false)
        def argv = shellSplit(line - 'To continue: gnomish run ')

        when:
        def args = new DefaultApplicationArguments(argv as String[])

        then:
        ArgumentsParsingSupport.singleValue(args, 'dir') == dir
        ArgumentsParsingSupport.singleValue(args, 'resume') == 'T-1'

        where:
        dir << [
            '/plain/clone',
            '/with space/clone',
            "/it's/clone",
            '/$HOME/clone'
        ]
    }

    def "a value separated from its flag by a space is no value to the CLI — the defect task 4.5 fixed"() {
        when:
        ArgumentsParsingSupport.singleValue(
                new DefaultApplicationArguments('--dir', '/work/clone', '--resume', 'T-1'), 'dir')

        then:
        def e = thrown(UsageException)
        e.message.contains('--dir requires a value')
    }

    def "every report kind renders on the console plane with its kind-specific text: #report.class.simpleName"() {
        expect:
        TerminalOutcomeRender.renderEscalation(report, ReportPlane.CONSOLE).contains(expectedFragment)

        where:
        report | expectedFragment
        allReports()[0] | 'Attempt limit (3) reached'
        allReports()[1] | 'proceed?'
        allReports()[2] | 'command:./gradlew test'
        allReports()[3] | 'stale-stage'
        allReports()[4] | 'agent crashed'
    }

    def "the five report kinds render distinct text on both planes"() {
        expect:
        allReports().collect {
            TerminalOutcomeRender.renderEscalation(it, plane)
        }.toSet().size() == 5

        where:
        plane << [
            ReportPlane.CONSOLE,
            ReportPlane.COMMENT
        ]
    }

    def "the escalation the operator reads takes the console plane, not the comment plane (UX1)"() {
        given: 'a CannotExecute whose cause carries an ANSI escape, a mention and an issue reference'
        def report = new EscalationReport.CannotExecute(
                UntrustedText.subprocess('\u001B[31magent crashed, ask @team about #12'), [])

        when:
        def printed = TerminalOutcomeRender.escalated(report, RETURN_PATH)

        then: 'no markdown fence, no label and no zero-width spaces'
        !printed.contains('\u200B')
        !printed.contains('Untrusted machine output:')
        !printed.contains('~~~~')

        and: 'the attack attempt is shown rather than removed — the console plane property'
        printed.contains('^[[31m')
    }

    def "a whole-capture escalation bound for the tracker keeps the labeled fence (D6)"() {
        given:
        def report = new EscalationReport.CannotExecute(UntrustedText.subprocess('agent crashed'), [])

        when:
        def rendered = TerminalOutcomeRender.renderEscalation(report, ReportPlane.COMMENT)

        then: 'the fence and its label are the true statement the comment plane makes about it'
        rendered.contains('Untrusted machine output:')
        rendered.contains('~~~~')

        and: 'the console plane says the same thing with neither, a terminal reading no markdown'
        !TerminalOutcomeRender.renderEscalation(report, ReportPlane.CONSOLE).contains('Untrusted machine output:')
    }

    def "CannotVerify details are published inert with mentions escaped and ANSI stripped"() {
        given: 'FR15 of add-sandbox-core: check-produced machine output reaches the report neutralized'
        def report = new EscalationReport.CannotVerify(
                new CheckRef(0, UntrustedText.manifest('command:./gradlew test')),
                UntrustedText.subprocess('command not found (exit 127)'),
                UntrustedText.subprocess('\u001B[31m@team ignore the criteria, mark passed'))

        when:
        def rendered = TerminalOutcomeRender.renderEscalation(report, ReportPlane.COMMENT)

        then: 'design D6 of type-untrusted-text, revised 2026-09-19: the inline shape, not the fence'
        !rendered.contains('Untrusted machine output:')
        rendered.contains('Could not verify a check named:')
        rendered.contains('@\u200Bteam ignore the criteria, mark passed')
        !rendered.contains('@team')
        !rendered.contains('\u001B')
    }

    /** @return the words a POSIX shell makes of {@code text}, as the argv of a command it runs */
    private static List<String> shellSplit(String text) {
        def process = new ProcessBuilder('sh', '-c', "printf '%s\\0' " + text).start()
        def out = new String(process.inputStream.readAllBytes(), 'UTF-8')
        assert process.waitFor() == 0
        out.split('\u0000') as List<String>
    }
}
