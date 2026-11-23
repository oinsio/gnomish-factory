package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.adapter.agent.program.AnswerOutcome
import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultMapping
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.untrustedtext.Provenance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * An invalid {@code transform} answer is the round's quality failure (FR20 of
 * extract-agent-round-runner; design D15, part 3), judged by {@link AgentRoundRunner} with {@link
 * FakeDialect} and mapped onto the engine's shape by the one {@link ProgramResultMapping}: a file the
 * one answer read refuses, or a final answer the dialect reports malformed — its structured-output
 * retries exhausted — is {@code failed/quality} with one finding naming the cause, so the engine burns
 * one attempt; the CLI is launched once, the factory starting no second request for the round. A
 * round that produced no result stays {@code failed/infrastructure}, which burns none. The next
 * attempt's prompt carrying the finding is the flow's, {@code InvalidAnswerAttemptFlowSpec} in
 * {@code :bootstrap}.
 */
@Timeout(60)
class AgentRoundInvalidAnswerSpec extends Specification {

    static final AttemptKey KEY = new AttemptKey('TASK-7', 'build', 0)

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    RunnerFixture fixture

    def setup() {
        fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent)
    }

    // Scenario: executor/agent-round-runner / An unparseable answer file burns one attempt
    def "FR20: a transform round that leaves text that is not JSON is failed/quality, its one finding naming the parse error and quoting at most the excerpt bound"() {
        given: 'far more text than the excerpt bound, its end the agent\'s last words'
        def written = 'not json at all ' + ('x' * (AnswerOutcome.EXCERPT_CHARS * 2)) + ' the last words'

        when:
        def result = transform('done\n', written)

        then: 'a quality failure in the factory\'s words, naming the parse error'
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.QUALITY
        result.reason().raw().startsWith("agent executor 'fake': wrote a malformed answer file: ")
        result.reason().provenance() == Provenance.FACTORY
        result.answer() == null

        and: 'the finding quotes what was written, within the excerpt bound, its end kept'
        result.reason().raw().endsWith('; it wrote: ' + result.details().raw())

        and: 'the details carry the same excerpt as agent text'
        result.details().provenance() == Provenance.AGENT
        result.details().length() <= AnswerOutcome.EXCERPT_CHARS
        result.details().raw().endsWith('the last words')

        and: 'the engine takes it as one finding, so it burns one attempt; the CLI ran once'
        def failed = engine(result)
        failed.failureClass() == ExecutionResult.FailureClass.QUALITY
        failed.findings()*.message() == [result.reason().raw()]
        failed.findings()*.details() == [result.details().raw()]
        fixture.agentLaunches.size() == 1
    }

    // Scenario: executor/agent-round-runner / Exhausted structured-output retries burn one attempt
    def "FR20: a transform round whose CLI reports its structured-output retries exhausted is failed/quality naming that, and no second request is started"() {
        when:
        def result = transform('answer exhausted\ndone\n', null)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.QUALITY
        result.reason().raw() == "agent executor 'fake': its final answer does not match the answer's schema: " +
                FakeDialect.RETRIES_EXHAUSTED
        result.details() == null

        and: 'one finding burns one attempt, and the CLI was launched once'
        def failed = engine(result)
        failed.failureClass() == ExecutionResult.FailureClass.QUALITY
        failed.findings()*.message() == [result.reason().raw()]
        fixture.agentLaunches.size() == 1
    }

    // Scenario: executor/agent-round-runner / An empty answer file is an invalid answer
    def "FR20: a transform round that leaves #what at its answer path is failed/quality naming the empty answer, and no question is raised"() {
        when:
        def result = transform('done\n', written)

        then:
        result.failureClass() == FailureClass.QUALITY
        result.reason().raw() == "agent executor 'fake': wrote an empty answer file"
        result.answer() == null

        and: 'the engine raises no question: the round is a quality failure, not a decision request'
        def mapped = ProgramResultMapping.toEngine(RunnerFixture.NAME, Role.TRANSFORM, KEY, result, [])
        mapped.answer() == null
        (mapped.status() as ExecutionResult.Failed).failureClass() == ExecutionResult.FailureClass.QUALITY

        where:
        what | written
        'an empty file' | ''
        'a file of white space only' | '  \n'
    }

    // Scenario: executor/agent-round-runner / A round with no result event stays an infrastructure failure
    def "FR20: a transform round #how is failed/infrastructure and burns no attempt, whatever its answer file holds"() {
        given:
        fixture.bounds = new ProgramBounds(Duration.ofHours(1), Duration.ofHours(1), Duration.ofMillis(350))

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), cli(), { Path answer, Path err ->
            Files.writeString(answer, 'not json')
        })

        then: 'an infrastructure failure: the engine escalates it and burns no attempt'
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().contains(words)
        engine(result).failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        engine(result).findings().isEmpty()

        where:
        how | cli || words
        'killed on its overall bound' | {
            -> FakeProcess.running()
        } || 'the overall bound of PT0.35S expired'
        'whose CLI exits having emitted no result event' | {
            -> RunnerFixture.exited(0, 'hello m s\n')
        } || 'no result event'
    }

    private ExecutorResult transform(String stdout, String written) {
        fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, stdout), { Path answer, Path err ->
            if (written != null) {
                Files.writeString(answer, written)
            }
        })
    }

    private static ExecutionResult.Failed engine(ExecutorResult result) {
        ProgramResultMapping.toEngine(RunnerFixture.NAME, Role.TRANSFORM, KEY, result, []).status() as ExecutionResult.Failed
    }
}
