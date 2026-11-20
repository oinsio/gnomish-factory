package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.ResultLevel
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.Executor
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR13 of migrate-verify-to-executor-contract (design D14, D18): the check dispatch resolves a
 * check's executor in the run's registry, binds the entry to the check and its working copy at
 * resolution, and hands the engine's port the {@link CheckRound} wrapper over the bound executor,
 * which runs a {@code verdict} request with the check's settings — {@code start} with no pending turn,
 * {@code poll} by the turn's key otherwise (FR2, D14) — and maps the contract result through the one
 * mapping, so a verdict answer crosses the port as a {@code VerdictReply}.
 */
class CheckDispatchSpec extends Specification {

    @TempDir
    Path workingCopy

    private static final VerifyCheck.Executor FILES = VerifyChecks.builtin('files', [files: ['present.txt']])

    private CheckDispatch dispatch(List<VerifyCheck> checks) {
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
            'i.md', checks, new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
        HandAssembledCheckDispatch.dispatch(definition, workingCopy)
    }

    private static StageExecutor.Request request(VerifyCheck.Executor check = FILES, PendingTurn turn = null) {
        new StageExecutor.Request(new TaskContext('TASK-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
        'i.md', [], new AutonomyLimits(3), AdvancementMode.AUTO), new com.github.oinsio.gnomish.domain.engine.port.Workspace() {}, 2, [],
        check, CheckRef.of(0, check), turn)
    }

    def "FR1, FR13: the files-exist check runs bound to its own working copy, not the round's, and its verdict crosses the port"() {
        given:
        Files.createFile(workingCopy.resolve('present.txt'))

        when:
        def result = dispatch([FILES]).bind(FILES, CheckRef.of(0, FILES), new DirectoryWorkspace(workingCopy)).execute(request())

        then:
        result.status() instanceof ExecutionResult.Completed
        (result.answer() as ExecutionResult.VerdictReply).answer() == new VerdictAnswer.Sarif([], null)
        result.trace().key().stage() == 'build'
        result.trace().key().attempt() == 2
    }

    def "FR1, FR13: a files-exist path missing from the bound working copy crosses as an error result of the reply"() {
        when:
        def result = dispatch([FILES]).bind(FILES, CheckRef.of(0, FILES), new DirectoryWorkspace(workingCopy)).execute(request())

        then:
        def reply = (result.answer() as ExecutionResult.VerdictReply).answer() as VerdictAnswer.Sarif
        reply.results()*.level() == [ResultLevel.ERROR]
    }

    def "FR2, D14: with no pending turn the round starts the bound executor and never polls it"() {
        given:
        def bound = Mock(Executor)

        when:
        def result = new CheckRound(bound, null, FILES).execute(request())

        then:
        1 * bound.start({
            it.role() == Role.VERDICT
        }) >> new ExecutorResult(Status.PENDING, null, new PendingKey('run-1'),
        null, null, Usage.knownZero(), [])
        0 * bound.poll(_, _)
        (result.status() as ExecutionResult.Pending).key() == new PendingKey('run-1')
    }

    def "FR2, D14: a #origin turn polls the bound executor by the turn's key in a verdict request, and never starts it"() {
        given:
        def bound = Mock(Executor)
        ExecutorRequest sent = null

        when:
        def result = new CheckRound(bound, null, FILES).execute(request(FILES, new PendingTurn(new PendingKey('run-1'), origin)))

        then:
        0 * bound.start(_)
        1 * bound.poll(_, new PendingKey('run-1')) >> { ExecutorRequest r, PendingKey k ->
            sent = r
            new ExecutorResult(Status.COMPLETED, null, null, null,
                    new com.fasterxml.jackson.databind.ObjectMapper().valueToTree([kind: 'verdict', sarif: [version: '2.1.0',
                            runs: [
                                [tool: [driver: [name: 'ci']], results: [
                                        [level: 'error', message: [text: 'red'],
                                            properties: [details: 'tail']]
                                    ]]
                            ]]]), Usage.knownZero(), [])
        }
        sent.role() == Role.VERDICT
        sent.policy() instanceof Policy.ReadOnly
        (result.answer() as ExecutionResult.VerdictReply).answer() == new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(ResultLevel.ERROR, new Finding('red', null, 'tail'))
        ], null)

        where:
        origin << PendingTurn.Origin.values()
    }

    def "FR13: a failed run of the bound built-in maps to the engine's failure, naming its reason"() {
        given: 'the working copy holds no path, so the run reads settings it cannot judge'
        def bad = VerifyChecks.builtin('files', [files: 'not-a-list'])

        when:
        def result = dispatch([bad]).bind(bad, CheckRef.of(0, bad), new DirectoryWorkspace(workingCopy)).execute(request())

        then:
        (result.status() as ExecutionResult.Failed).failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
    }

    def "FR13: the round sends the bound executor a read-only verdict request with the check's settings, and maps #status"() {
        given:
        def check = VerifyChecks.external('probe', 'http', [url: 'http://127.0.0.1/'], java.time.Duration.ofSeconds(1),
        java.time.Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [])
        def bound = Mock(Executor)
        ExecutorRequest sent = null

        when:
        def result = new CheckRound(bound, null, check).execute(request())

        then:
        1 * bound.start(_) >> { ExecutorRequest r ->
            sent = r
            answer
        }
        sent.role() == Role.VERDICT
        sent.policy() instanceof Policy.ReadOnly
        sent.settings() == ExecutorContractJson.settingsTree([url: 'http://127.0.0.1/'])
        expected.isInstance(result.status())

        where:
        status | answer || expected
        'pending' | new ExecutorResult(Status.PENDING, null, new PendingKey('k'), null, null, Usage.knownZero(), []) || ExecutionResult.Pending
        'failed' | new ExecutorResult(Status.FAILED, FailureClass.QUALITY, null, UntrustedText.factory('red'), null,
                Usage.knownZero(), []) || ExecutionResult.Failed
    }

    def "FR13: a check whose executor binds no check round is refused at resolution"() {
        given:
        def stageOnly = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'command', 'c', [:],
        java.time.Duration.ZERO, java.time.Duration.ZERO, VerifyCheck.TimeoutClass.QUALITY, [],
        VerifyCheck.VerifyIn.SAME_BOX, com.github.oinsio.gnomish.domain.pipeline.Threshold.ERROR, [])

        when:
        dispatch([]).bind(stageOnly, CheckRef.of(0, stageOnly), new DirectoryWorkspace(workingCopy))

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "executor 'command' runs no check round in this run"
    }
}
