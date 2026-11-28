package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultMapping
import com.github.oinsio.gnomish.adapter.agent.runner.RunnerFixture
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRoundAnswers
import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerdictDerivation
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.AnswerReading
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The Claude dialect's schema-enforced answer through the unchanged runner and the engine's one
 * mapping and derivation (FR3, FR4 of extract-agent-round-runner): a {@code verdict} round's
 * structured output becomes the SARIF verdict the result carries, judged by the check's threshold;
 * an output that does not match the schema resolves as cannot-verify, never as a pass.
 *
 * <p>FR3, FR4 of extract-agent-round-runner.
 */
@Timeout(60)
class ClaudeAnswerRoundSpec extends Specification {

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    RunnerFixture fixture

    def setup() {
        fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent,
        otherDialect: new ClaudeDialect('claude', UntrustedText.subprocess('2.1.0'), 'api.anthropic.com'))
    }

    // Scenario: executor/claude-dialect / Judge verdict arrives as SARIF
    def "FR4: a verdict round's structured output is the SARIF verdict its result carries, judged #verdict"() {
        when:
        def result = fixture.start(RunnerFixture.request(Role.VERDICT), RunnerFixture.exited(0, stream(output)))

        then:
        result.status() == Status.COMPLETED
        def reading = Answer.read(result.answer(), { String text ->
            UntrustedText.agent(text)
        })
        reading instanceof AnswerReading.Parsed
        (reading as AnswerReading.Parsed).answer() instanceof Answer.Verdict
        fixture.agentLaunches[0].command().contains('--json-schema')

        and:
        def derived = derive(result)
        verdict.isInstance(derived)
        derived.findings()*.message() == findings

        where:
        output || verdict | findings
        '{"passed":true,"findings":["nit"]}' || Verdict.Pass | ['nit']
        '{"passed":false,"findings":["no tests"]}' || Verdict.Fail | ['no tests']
    }

    // Scenario: executor/claude-dialect / Malformed final output
    def "FR4: a verdict round whose final output does not match the schema resolves as cannot-verify"() {
        when:
        def result = fixture.start(RunnerFixture.request(Role.VERDICT), RunnerFixture.exited(0, stream('{"passed":"yes"}')))

        then:
        result.status() == Status.FAILED
        result.reason().forLog().contains("does not match the answer's schema")
        derive(result) instanceof Verdict.CannotVerify
    }

    // FR22 of define-executor-contract; FR4 of extract-agent-round-runner: a judge's verdict request is
    //     an agent round, so the cost its result event reports per model reaches the engine's result,
    //     and with it the check's verdict participant entry (VerifyOrchestrator builds it from that map)
    // Scenario: executor/agent-executor / Judge vote carries cost
    def "FR22: a judge's verdict round carries the cost its result event reports, per model, into its verdict entry"() {
        given:
        def priced = '{"type":"system","subtype":"init","session_id":"s","model":"claude-x"}\n' +
                '{"type":"result","subtype":"success","session_id":"s","result":"","structured_output":{"passed":true,"findings":[]},' +
                '"modelUsage":{"claude-x":{"inputTokens":10,"outputTokens":20,"cacheCreationInputTokens":0,' +
                '"cacheReadInputTokens":5,"costUSD":0.25},"claude-y":{"inputTokens":1,"outputTokens":2,' +
                '"cacheCreationInputTokens":0,"cacheReadInputTokens":0}}}\n'

        when:
        def result = fixture.start(RunnerFixture.request(Role.VERDICT), RunnerFixture.exited(0, priced))
        def engine = ProgramResultMapping.toEngine(RunnerFixture.NAME, Role.VERDICT, new AttemptKey('TASK-7', 'build', 0), result, [])
        def entry = Participants.verdict('claude', 0, engine.usage().byModel())

        then: 'the priced model carries its cost, the other none'
        entry.byModel()['claude-x'] == new ModelUsage(new TokenUsage(10, 20, 0, 5), new BigDecimal('0.25'))
        entry.byModel()['claude-y'] == new ModelUsage(new TokenUsage(1, 2, 0, 0), null)
    }

    private static Verdict derive(ExecutorResult result) {
        def engine = ProgramResultMapping.toEngine(RunnerFixture.NAME, Role.VERDICT, new AttemptKey('TASK-7', 'build', 0), result, [])
        VerdictDerivation.derive(CheckRoundAnswers.of(engine), Threshold.DEFAULT)
    }

    private static String stream(String output) {
        '{"type":"system","subtype":"init","session_id":"s","model":"claude-x"}\n' +
                '{"type":"result","subtype":"success","session_id":"s","result":"","structured_output":' + output + '}\n'
    }
}
