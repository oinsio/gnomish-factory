package com.github.oinsio.gnomish.testfixtures.standin

import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.SettingsJudgement
import com.github.oinsio.gnomish.executorcontract.SettingsSchema
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR12 of define-executor-contract (design D18): the reference executor and the misbehaving fake of
 * the stand-in library do what the executor table says. The reference answers describe, start in
 * each live role, a deferred start then its poll (FR23), and refuses a role its describe does not
 * list (no cancel, NG16); {@code break-<case>} breaks exactly the one case its link names, in that case's workspace, and answers as the reference
 * elsewhere. Requests are written by the contract's own writer, as {@code ProgramExecutor} writes
 * them. Lives beside {@link StandInLibrarySpec}, whose per-preset features cover these presets' rows.
 */
class StandInExecutorSpec extends Specification {

    @TempDir
    Path tempDir

    def "FR12: the reference answers start in role #role with that role's answer, its logs on stdout and stderr"() {
        when:
        def run = run(StandIn.executor('reference'), request(role, 'transform'), 'start')

        then:
        run.exit == 0
        run.stdout == StandIn.data('stdout-executor#progress')
        run.stderr == StandIn.data('stderr-executor#start-noise')
        result(run).status() == Status.COMPLETED
        result(run).answer()?.path('kind')?.asText() == kind
        result(run).answer()?.path('chosen')?.asText() == chosen

        where:
        role || kind | chosen
        Role.TRANSFORM || null | null
        Role.VERDICT || 'verdict' | ''
        Role.DECISION || 'select' | 'say "main"'
    }

    def "FR12, FR23: the reference defers a start whose settings ask it, its poll with that key answers the role, another key is refused"() {
        given:
        def deferred = request(Role.DECISION, 'pending-poll', [defer: true])

        when:
        def started = run(StandIn.executor('reference'), deferred, 'start')
        def polled = run(StandIn.executor('reference'), deferred, 'poll')
        def foreign = run(StandIn.executor('reference'), deferred, 'poll', 'another-key')

        then:
        result(started).status() == Status.PENDING && result(started).pendingKey().value() == 'reference-deferred'
        result(polled).status() == Status.COMPLETED
        result(polled).answer().path('chosen').asText() == 'say "main"'
        foreign.exit == 2 && foreign.result == null && foreign.stderr.contains("no pending run has the key 'another-key'")
    }

    def "FR12: the reference describes three roles, poll as its one capability and a settings schema, and refuses another role"() {
        when:
        def described = run(StandIn.executor('reference'), null, 'describe')
        def refused = run(StandIn.executor('reference'), request(Role.EFFECT, 'unknown-role'), 'start')
        def describe = ExecutorContractJson.reader().readTree(described.result)

        then:
        described.result == StandIn.data('executor-describe#reference')
        describe.path('roles').toString() == '["transform","verdict","decision"]'
        describe.path('capabilities').toString() == '["poll"]'
        SettingsSchema.judge(describe.path('settingsSchema'), [defer: true]) instanceof SettingsJudgement.Accepted
        SettingsSchema.judge(describe.path('settingsSchema'), [defer: 'yes']) instanceof SettingsJudgement.Rejected
        refused.exit == 2 && refused.result == null && refused.stderr.contains('names no role this executor answers')
    }

    def "FR12: break-#kase breaks its case's #operation in its own workspace and answers as the reference in another"() {
        given:
        def fake = StandIn.executor("break-${kase}")
        Map<String, Object> settings = operation == 'poll' ? [defer: true] : [:]

        when:
        def broken = run(fake, request(role, kase, settings, extra), operation)
        def elsewhere = run(fake, request(role, 'elsewhere', settings, extra), operation)
        def reference = run(StandIn.executor('reference'), request(role, 'elsewhere', settings, extra), operation)

        then:
        breaksItsCase(kase, broken)
        elsewhere.exit == reference.exit
        elsewhere.result == reference.result

        where:
        kase | operation | role | extra
        'transform' | 'start' | Role.TRANSFORM | false
        'verdict' | 'start' | Role.VERDICT | false
        'decision' | 'start' | Role.DECISION | false
        'unknown-role' | 'start' | Role.EFFECT | false
        'oversized-output' | 'start' | Role.TRANSFORM | false
        'stderr-noise' | 'start' | Role.TRANSFORM | false
        'non-json-stdout' | 'start' | Role.TRANSFORM | false
        'pending-poll' | 'poll' | Role.DECISION | false
        'unknown-request-fields' | 'start' | Role.TRANSFORM | true
        'malformed-answer' | 'start' | Role.DECISION | false
    }

    def "FR12: break-#kase answers a describe that breaks its case, and break of another case the reference's"() {
        when:
        def broken = run(StandIn.executor("break-${kase}"), null, 'describe')
        def other = run(StandIn.executor('break-transform'), null, 'describe')
        def read = ExecutorContractJson.reader().readTree(broken.result)

        then:
        broken.result == StandIn.data("executor-describe#break-${kase}")
        other.result == StandIn.data('executor-describe#reference')
        kase != 'describe' || EnforcementLevel.fromWire(read.path('enforcementLevel').asText()).isEmpty()
        kase != 'settings-schema' || SettingsSchema.judge(read.path('settingsSchema'), [:]) instanceof SettingsJudgement.Unusable

        where:
        kase << ['describe', 'settings-schema']
    }

    def "FR12: break-<case> is one link per JVM onto the fake; a case the kit does not name, and the fake's own link, are refused"() {
        given:
        Path fake = StandIn.executor('break-unknown-role')

        expect:
        StandIn.executor('break-unknown-role') == fake
        fake.fileName.toString() == 'break-unknown-role'
        Files.readSymbolicLink(fake) == StandIn.preset('break')

        when:
        def unknown = run(StandIn.executor('break-nothing'), null, 'describe')
        def own = run(StandIn.preset('break'), null, 'describe')

        then:
        unknown.exit == 97
        unknown.stderr.contains("'break-nothing' names no conformance case")
        run(StandIn.executor('break-cancel'), null, 'describe').exit == 97
        own.exit == 97
        own.stderr.contains('export-name needs a per-run link')
    }

    private static String request(Role role, String workspace, Map<String, Object> settings = [:], boolean extra = false) {
        RoleInputs inputs = role == Role.TRANSFORM ? RoleInputs.transform('Implement the task.')
                : role == Role.VERDICT ? RoleInputs.verdict('Every method is documented.')
                : role == Role.DECISION ? RoleInputs.decision('Which base?', ['say "main"', 'release/1.x'])
                : RoleInputs.none()
        Policy policy = role == Role.TRANSFORM ? new Policy.WorkspaceWrite() : new Policy.ReadOnly()
        def written = new ExecutorRequest(role, new Workspace("/work/${workspace}"), policy, inputs,
                ExecutorContractJson.settingsTree(settings), Bounds.none(), [], null)
        ObjectNode tree = ExecutorContractJson.reader().readTree(ExecutorContractJson.writer().writeValueAsString(written)) as ObjectNode
        if (extra) {
            tree.put('x-gnomish-conformance-probe', 'a field no executor of protocol 1 knows')
        }
        ExecutorContractJson.writer().writeValueAsString(tree)
    }

    /** Whether {@code run}, the fake's run in the workspace of case {@code kase}, shows that case broken. */
    private static boolean breaksItsCase(String kase, Map run) {
        switch (kase) {
            case 'transform': return run.exit == 0 && run.result == null
            case 'verdict': return result(run).status() == Status.COMPLETED && result(run).answer() == null
            case 'decision': return result(run).answer().path('chosen').asText() == 'not-an-option'
            case 'unknown-role': return run.exit == 0 && result(run).status() == Status.COMPLETED
            case 'oversized-output': return run.result.length() > 1024 * 1024
            case 'stderr-noise': return run.result == null && run.stderr.contains('"status":"completed"')
            case 'non-json-stdout': return run.result == null && run.stdout.contains('"status":"completed"')
            case 'pending-poll': return run.exit == 0 && run.result == null
            case 'unknown-request-fields':
                return run.exit == 2 && run.result == null && run.stderr != StandIn.data('stderr-executor#start-noise')
            case 'malformed-answer': return result(run).answer().path('options').size() == 1
            default: throw new IllegalArgumentException("no conformance case '${kase}'")
        }
    }

    private static ExecutorResult result(Map run) {
        ExecutorContractJson.reader().forType(ExecutorResult).readValue(run.result as String)
    }

    /** Runs {@code binary} with its request on stdin, the result file beside it, a poll's key; exit, stdout, stderr, result. */
    private Map run(Path binary, String request, String operation, String key = 'reference-deferred') {
        Path dir = Files.createTempDirectory(tempDir, 'run-')
        Path stdin = Files.writeString(dir.resolve('request.json'), request ?: '')
        Path resultFile = dir.resolve('result.json')
        def builder = new ProcessBuilder([binary.toString(), operation]).directory(dir.toFile()).redirectInput(stdin.toFile())
        TestChildEnvironment.cleared(builder).putAll([GNOMISH_RESULT_FILE: resultFile.toString(), GNOMISH_PENDING_KEY: operation == 'poll' ? key : ''])
        Process process = builder.start()
        String stdout = process.inputStream.text
        String stderr = process.errorStream.text
        [exit: process.waitFor(), stdout: stdout, stderr: stderr, result: Files.exists(resultFile) ? resultFile.toFile().text : null]
    }
}
