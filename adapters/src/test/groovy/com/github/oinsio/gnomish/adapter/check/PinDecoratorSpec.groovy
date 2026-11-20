package com.github.oinsio.gnomish.adapter.check

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.adapter.check.http.HttpExecutor
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.AnswerReading
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.Executor
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.gitobjects.CommitRequest
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.gitobjects.TreeEdit
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR16 of add-sandbox-core; FR6, FR16 of migrate-verify-to-executor-contract (design D8, D17): the pin
 * decorator over a check's bound executor. The pin set is the law's {@code pinPaths} united with the
 * {@code checkId} exactly when the executor's describe declares {@code checkIdIsPinnedPath} — the one
 * per-executor input, so the features run over both values of the flag. The set is byte-compared
 * against the law commit as bare git objects at the attempt commit before each {@code start} and
 * {@code poll}: a diff is an {@code error} result per path and the executor is never reached; an empty
 * set leaves the executor unwrapped; a pin that cannot be evaluated is {@code failed/infrastructure}.
 * Runs on real temporary repositories, no git mocking.
 */
class PinDecoratorSpec extends Specification implements PinCheckFixture {

    static final String WORKFLOW = '.github/workflows/ci.yml'

    @TempDir
    Path tempDir

    def calls = []

    def executor = new Executor() {
        Describe describe() {
            flagged(false)
        }

        ExecutorResult start(ExecutorRequest request) {
            calls << 'start'
            ok()
        }

        ExecutorResult poll(ExecutorRequest request, PendingKey key) {
            calls << 'poll'
            ok()
        }
    }

    def setup() {
        seedPinnedRepo(tempDir)
    }

    static Describe flagged(boolean checkIdIsPinnedPath) {
        new Describe(Describe.SPOKEN_PROTOCOL_VERSION, UntrustedText.factory('test'), [Role.VERDICT], [],
        JsonNodeFactory.instance.objectNode(), [], [], null, [], EnforcementLevel.PROMPT_ONLY, true,
        checkIdIsPinnedPath)
    }

    static ExecutorResult ok() {
        new ExecutorResult(Status.COMPLETED, null, null, null, null, Usage.knownZero(), [])
    }

    static ExecutorRequest request() {
        new ExecutorRequest(Role.VERDICT, new Workspace('.'), new Policy.ReadOnly(), RoleInputs.verdict('check'),
                ExecutorContractJson.settingsTree([:]), Bounds.none(), [], null)
    }

    private ObjectId attemptWith(Map<String, String> files) {
        gitObjects.commit(new CommitRequest('refs/heads/gnomish/task-1', Optional.empty(), baseTip,
                files.collect { path, text ->
                    text == null ? new TreeEdit.DeletePath(path) : new TreeEdit.PutFile(path, bytes(text))
                }, metadata()))
    }

    private Executor guarded(List<String> pinPaths, boolean flag, def workspace, ObjectId law = baseTip) {
        new PinCheck(gitObjects, law).guarding(executor, check(pinPaths), flagged(flag), workspace)
    }

    /** The paths of the error results a refusal answers, or null when the result is not such an answer. */
    static List<String> refusedPaths(ExecutorResult result) {
        if (result.status() != Status.COMPLETED || result.answer() == null) {
            return null
        }
        def verdict = verdictOf(result)
        verdict.runs().collectMany { run -> run.results() }.collect {
            assert it.level() == SarifLevel.ERROR
            it.uri().raw()
        }
    }

    static Answer.Verdict verdictOf(ExecutorResult result) {
        (Answer.read(result.answer(), { String text ->
            UntrustedText.factory(text)
        }) as AnswerReading.Parsed).answer() as Answer.Verdict
    }

    // Scenario: executor/wire-contract / Declared flag pins the check id
    // Scenario: executor/wire-contract / Absent flag pins nothing of its own
    def "FR16: the pin set is the law's pinPaths, plus the check id exactly when the describe declares it (flag #flag)"() {
        expect:
        PinCheck.pinSet(check(pinPaths), flagged(flag)) as List == expected

        where:
        flag | pinPaths || expected
        true | [] || [WORKFLOW]
        true | ['config/analyzer.yml'] || [
            '.github/workflows/ci.yml',
            'config/analyzer.yml'
        ]
        true | [WORKFLOW] || [WORKFLOW]
        false | [] || []
        false | ['config/analyzer.yml'] || ['config/analyzer.yml']
    }

    // Scenario: verification/http-check-provider / Only law-declared paths pin an http check
    def "FR16: the http built-in's describe contributes nothing, so its pin set is exactly the law's paths"() {
        expect:
        !HttpExecutor.describeAnswer().checkIdIsPinnedPath()
        PinCheck.pinSet(check(['config/analyzer.yml']), HttpExecutor.describeAnswer()) as List == ['config/analyzer.yml']
        PinCheck.pinSet(check([]), HttpExecutor.describeAnswer()).isEmpty()
    }

    // Scenario: verification/verification-hardening / Rewritten workflow is caught before the adapter is invoked
    def "FR16: a rewritten check-id file under the flag fails the check and the executor is never started"() {
        given:
        def attempt = attemptWith([(WORKFLOW): "name: ci\non: [push]\nsteps: echo ok\n"])
        def logs = LogCaptureSupport.attach(PinCheck)

        when:
        def result = guarded([], true, workspaceAt(attempt)).start(request())

        then: 'one error result naming the file, and no executor contact'
        refusedPaths(result) == [WORKFLOW]
        calls.isEmpty()

        and: 'FR15 of harden-logging-observability: the refusal is a coded WARN naming the check'
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.EXTERNAL_CHECK_PIN_MISMATCH.head())
        }
        warned.level == Level.WARN
        warned.formattedMessage.contains(WORKFLOW)

        cleanup:
        logs.detach()
    }

    def "FR16: without the flag the same rewritten check-id file is not pinned, and the executor runs"() {
        given:
        def attempt = attemptWith([(WORKFLOW): 'name: weakened'])

        when:
        def result = guarded([], false, workspaceAt(attempt)).start(request())

        then:
        result == ok()
        calls == ['start']
    }

    // Scenario: pipeline/pipeline-config / Pin paths are addressed from the repository root
    def "FR16: a law-declared path is compared from the repository root, with or without the flag (#flag)"() {
        given:
        def attempt = attemptWith(['config/analyzer.yml': 'rules: none'])

        when:
        def result = guarded(['config/analyzer.yml'], flag, workspaceAt(attempt)).start(request())

        then:
        refusedPaths(result) == ['config/analyzer.yml']
        calls.isEmpty()

        where:
        flag << [true, false]
    }

    def "FR16: a pinned path #how is a diff"() {
        given:
        def attempt = attemptWith([(path): text])

        when:
        def result = guarded([path], false, workspaceAt(attempt)).start(request())

        then:
        def verdict = verdictOf(result)
        verdict.runs()[0].results()[0].message().raw() == "pinned definition file '" + path + "' " + how
        calls.isEmpty()

        where:
        path | text || how
        'config/analyzer.yml' | null || 'was removed relative to the base branch'
        'config/extra.yml' | 'planted' || 'is absent from the base branch'
        'config/analyzer.yml' | 'rules: none' || 'differs from the base branch'
    }

    def "FR16: untouched pins, and a pinned path absent from both sides, reach the executor"() {
        given:
        def attempt = attemptWith(['src/Main.java': 'code'])

        when:
        def started = guarded(['config/never-existed.yml'], true, workspaceAt(attempt)).start(request())

        then:
        started == ok()
        calls == ['start']
    }

    // Scenario: verification/verification-hardening / A check with no declared pin paths passes the pin vacuously
    def "FR16: an empty pin set leaves the executor unwrapped and needs no attempt commit"() {
        expect:
        guarded([], false, new FakeWorkspace()).is(executor)
    }

    // Scenario: verification/github-external-check / Early substitution is caught at the point of use
    // Scenario: verification/verification-hardening / Early substitution is caught at the point of use
    def "FR16: a substitution two stages back is caught at the point of use, on start and on a poll alike"() {
        given: 'the workflow weakened at stage 1, then honest work on top of it'
        def stage1 = attemptWith([(WORKFLOW): 'name: weakened'])
        def stage3 = gitObjects.commit(new CommitRequest('refs/heads/gnomish/task-1', Optional.of(stage1), stage1,
                [
                    new TreeEdit.PutFile('src/Main.java', bytes('honest work'))
                ], metadata(1_700_000_100L)))
        def pinned = guarded([], true, workspaceAt(stage3))

        expect: 'compared with the law commit, not the previous round'
        refusedPaths(pinned.start(request())) == [WORKFLOW]
        refusedPaths(pinned.poll(request(), new PendingKey('k-1'))) == [WORKFLOW]
        calls.isEmpty()
    }

    def "FR16: a pin that cannot be evaluated is failed/infrastructure and the executor is not reached: #why"() {
        when:
        def result = new PinCheck(gitObjects, law ? baseTip : null)
                .guarding(executor, check([]), flagged(true), workspace.call(this)).start(request())

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().contains(reason)
        calls.isEmpty()

        where:
        why | law | workspace || reason
        'the workspace carries no commit' | true | { s ->
            new FakeWorkspace()
        } || 'attempt-commit workspace'
        'the law is bound to no commit' | false | { s ->
            s.workspaceAt(s.attemptWith(['src/Main.java': 'code']))
        } || 'law commit'
    }

    // FR16, NFR-C1 of add-sandbox-core: a pinned blob over the read cap cannot be compared.
    def "FR16: a pinned file over the read cap is failed/infrastructure naming the path"() {
        given:
        def oversized = new String(new char[(int) PinCheck.PIN_READ_CAP_BYTES + 1]).replace('\u0000', 'x')
        def attempt = attemptWith([(WORKFLOW): oversized])

        when:
        def result = guarded([], true, workspaceAt(attempt)).start(request())

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().contains('oversized')
        result.details().raw().contains(WORKFLOW)
        calls.isEmpty()
    }

    // Scenario: git/git-task-persistence / The law and the pin guard bind from the pinned SHA
    // Scenario: git/base-ref-resolution / Pin guard and law share one commit
    def "FR16: the pin compares against the bound commit, not the base branch's later tip"() {
        given: 'an attempt cut from the bound commit, every pinned file untouched'
        def attempt = attemptWith(['src/Main.java': 'code'])
        def pinned = guarded([], true, workspaceAt(attempt))

        and: 'the base branch moves on with a different workflow file after the binding'
        gitObjects.commit(new CommitRequest('refs/heads/base', Optional.of(baseTip), baseTip,
                [
                    new TreeEdit.PutFile(WORKFLOW, bytes('name: changed'))
                ], metadata()))

        when:
        def result = pinned.poll(request(), new PendingKey('k-1'))

        then:
        result == ok()
        calls == ['poll']
    }

    def "FR16: a path both declared and flagged is compared once"() {
        given:
        def attempt = attemptWith([(WORKFLOW): 'name: changed'])

        expect:
        refusedPaths(guarded([WORKFLOW], true, workspaceAt(attempt)).start(request())) == [WORKFLOW]
    }

    def "the decorator answers its executor's describe"() {
        given:
        def attempt = attemptWith(['src/Main.java': 'code'])

        expect:
        guarded([], true, workspaceAt(attempt)).describe() == flagged(false)
    }

    private static byte[] bytes(String text) {
        text.getBytes(StandardCharsets.UTF_8)
    }
}
