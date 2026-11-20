package com.github.oinsio.gnomish.adapter.check

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.ResultLevel
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, NFR-R2 of add-manual-run; FR5 of migrate-verify-to-executor-contract (D5 second amendment):
 * the runner reads the findings path whatever the exit code but 126/127. A SARIF log is the answer
 * as written, a valid legacy {@code {"findings":[…]}} file is normalized to {@code error} results,
 * and either replaces the synthetic result; a file the reading refuses is warned about, and a red
 * command then keeps its synthetic tail result — a broken reporter never masks it.
 */
class ShellCommandCheckRunnerFindingsSpec extends Specification implements ShellCommandCheckRunnerTestSupport {

    @TempDir
    Path tempDir

    def runner = new ShellCommandCheckRunner(new VirtualClock())

    private static String sarif(String level) {
        '{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"lint"}},"results":[{"level":"' + level +
                '","message":{"text":"reported on green"},"locations":[{"physicalLocation":' +
                '{"artifactLocation":{"uri":"src/A.java"},"region":{"startLine":3}}}]}]}]}'
    }

    // Scenario: verification/verdict-executors / Structured findings replace the synthetic result
    def "exit 1 with two valid legacy findings answers exactly those two, with no synthetic one"() {
        given: 'a command that exits 1 and writes two valid findings to the findings file'
        def check = command('''cat > "$GNOMISH_FINDINGS_FILE" <<'EOF'
{"findings":[{"message":"first problem","location":"a.txt:1"},{"message":"second problem","details":"more info"}]}
EOF
exit 1''')

        when:
        def outcome = runner.probe(check, workspace())

        then:
        def findings = [
            new Finding('first problem', 'a.txt:1', null),
            new Finding('second problem', null, 'more info')
        ]
        answerOf(outcome).results()*.level() == [
            ResultLevel.ERROR,
            ResultLevel.ERROR
        ]
        findingsOf(outcome) == findings
        derived(outcome) == new Verdict.Fail(findings)
    }

    // Scenario: verification/verdict-executors / A broken reporter cannot mask a red command
    // Scenario: verification/verdict-executors / A malformed findings file is logged
    def "a malformed findings file on a red command is warned about and the synthetic result stands"() {
        given: 'a command that exits 1 and writes unparseable JSON to the findings file'
        def check = command('echo boom-output; echo "not valid json {{{" > "$GNOMISH_FINDINGS_FILE"; exit 1')

        when:
        ShellOutcome outcome = null
        def events = capture(CheckFindings) {
            outcome = runner.probe(check, workspace())
        }

        then: 'the check fails, not CannotVerify, on the synthetic tail result'
        def findings = findingsOf(outcome)
        findings.size() == 1
        findings[0].message() == 'command exited with status 1'
        findings[0].details().contains('boom-output')
        derived(outcome) instanceof Verdict.Fail

        and:
        events.any {
            it.level == Level.WARN && it.formattedMessage.startsWith(OperatorEvent.FINDINGS_FILE_MALFORMED.head())
        }
    }

    def "a red command whose file holds #shape degrades to the synthetic result"() {
        given:
        def check = command('echo boom-output; cat > "$GNOMISH_FINDINGS_FILE" <<\'EOF\'\n' + content + '\nEOF\nexit 1')

        when:
        def outcome = runner.probe(check, workspace())

        then:
        def findings = findingsOf(outcome)
        findings.size() == 1
        findings[0].message() == 'command exited with status 1'
        findings[0].details().contains('boom-output')

        where:
        shape | content
        'no findings array' | '{"unrelated":true}'
        'a blank message' | '{"findings":[{"message":""}]}'
        'an empty findings array' | '{"findings":[]}'
        'a SARIF log with no results' | '{"version":"2.1.0","runs":[]}'
    }

    // Scenario: verification/verdict-executors / A findings file on a green command is read
    def "a findings file on a green command is read: a warning result passes carrying it, an error result fails"() {
        given:
        def check = command('cat > "$GNOMISH_FINDINGS_FILE" <<\'EOF\'\n' + sarif(level) + '\nEOF\nexit 0')

        when:
        def outcome = runner.probe(check, workspace())

        then:
        def finding = new Finding('reported on green', 'src/A.java:3', null)
        answerOf(outcome) == new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(answered, finding)
        ], null)
        derived(outcome) == expected

        where:
        level | answered || expected
        'warning' | ResultLevel.WARNING || new Verdict.Pass(null, [
            new Finding('reported on green', 'src/A.java:3', null)
        ])
        'error' | ResultLevel.ERROR || new Verdict.Fail([
            new Finding('reported on green', 'src/A.java:3', null)
        ])
    }

    // Scenario: verification/verdict-executors / Warnings pass under the default threshold
    def "the exit code carries no verdict: a red command whose SARIF holds only warnings passes with them"() {
        given:
        def check = command('cat > "$GNOMISH_FINDINGS_FILE" <<\'EOF\'\n' + sarif('warning') + '\nEOF\nexit 1')

        when:
        def outcome = runner.probe(check, workspace())

        then:
        derived(outcome) == new Verdict.Pass(null, [
            new Finding('reported on green', 'src/A.java:3', null)
        ])
    }

    def "a legacy findings file on a green command is normalized to error results and fails the check"() {
        given:
        def check = command('''cat > "$GNOMISH_FINDINGS_FILE" <<'EOF'
{"findings":[{"message":"reported on green","location":"src/A.java:3"}]}
EOF
exit 0''')

        when:
        def outcome = runner.probe(check, workspace())

        then:
        def finding = new Finding('reported on green', 'src/A.java:3', null)
        answerOf(outcome) == new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(ResultLevel.ERROR, finding)
        ], null)
        derived(outcome) == new Verdict.Fail([finding])
    }

    // NFR-R2 of add-manual-run, FR5 of harden-logging-observability: a broken reporter on a green
    //     command is warned about, naming the check, and the green answer stands without findings.
    def "a malformed findings file on a green command is warned about and the answer stays an empty pass"() {
        given:
        def check = command('''echo 'not json' > "$GNOMISH_FINDINGS_FILE"; exit 0''')

        when:
        ShellOutcome outcome = null
        def events = capture(CheckFindings) {
            outcome = runner.probe(check, workspace())
        }

        then:
        outcome == new ShellOutcome.Answered([])
        events.any {
            it.level == Level.WARN &&
            it.formattedMessage.startsWith(OperatorEvent.FINDINGS_FILE_MALFORMED.head()) &&
            it.formattedMessage.contains('not json')
        }
    }

    def "a green command with no findings file content logs no warning"() {
        when:
        ShellOutcome outcome = null
        def events = capture(CheckFindings) {
            outcome = runner.probe(command('exit 0'), workspace())
        }

        then:
        outcome == new ShellOutcome.Answered([])
        events.empty
    }
}
