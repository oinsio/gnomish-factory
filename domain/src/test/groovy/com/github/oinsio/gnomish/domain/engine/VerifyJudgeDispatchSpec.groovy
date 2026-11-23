package com.github.oinsio.gnomish.domain.engine

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.domain.pipeline.ExecutorSettings
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyingStages
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.JudgeChecks
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * VerifyOrchestrator judge dispatch (design D2 of extract-agent-round-runner): a {@code judge}
 * check is one {@code verdict} request through the check rounds every executor check takes — its
 * check-round view names the executor its block resolves to, carries the block's settings and
 * model, and the criteria file as its identifier — and its verdict is derived from the answer by
 * the one verdict derivation; the request's reported usage is the check's {@code verdict}
 * participant entry, its {@code vote} the request's ordinal among the round's judge checks, and the
 * task context reaches the request unmodified (FR4, M3 of extract-agent-round-runner; FR3, FR7,
 * NFR-C1 of add-stage-engine).
 */
class VerifyJudgeDispatchSpec extends VerifyOrchestratorSpecBase {

    static VerdictAnswer failing(String... messages) {
        new VerdictAnswer.Sarif(messages.collect {
            new VerdictAnswer.Result(ResultLevel.ERROR, new Finding(it, null, null))
        }, null)
    }

    // FR4: the one request's failing verdict is the check's, with its findings
    // Scenario: pipeline/stage-engine / The judge's verdict is the check's verdict
    def "a judge's failing verdict with two findings fails the check with those findings after exactly one request"() {
        given: 'the judge check round answers a failing verdict carrying two findings'
        def judges = new ScriptedCheckRounds([
            failing('missing test', 'wrong name')
        ])

        when: 'the single judge check is verified'
        def result = orchestrator(new ScriptedCheckRounds(), new ScriptedCheckRounds(), new ScriptedCheckRounds(), judges)
                .verify(VerifyingStages.of([judge()]), CONTEXT, WORKSPACE, KEY, TaskState.atStageStart('build'), null)

        then: 'the check fails with exactly those two findings'
        def verdict = result.results()[0].verdict() as Verdict.Fail
        verdict.findings()*.message() == ['missing test', 'wrong name']

        and: 'exactly one request was made'
        judges.requests.size() == 1
    }

    // FR4, M3: the request is the judge's check-round view — the entry its agent block resolves to,
    //     the block's settings with the pinned model, the criteria file as the identifier
    def "the judge's request names the block's executor, settings and model, and the criteria file"() {
        given:
        def judges = new ScriptedCheckRounds([new Verdict.Pass(null, [])])

        when:
        orchestrator(new ScriptedCheckRounds(), new ScriptedCheckRounds(), new ScriptedCheckRounds(), judges)
                .verify(VerifyingStages.of([
                    JudgeChecks.agent('review.md', 'opus', [roundTimeout: '60s'])
                ]),
                CONTEXT, WORKSPACE, KEY, TaskState.atStageStart('build'), null)

        then:
        def check = judges.calls[0]
        check.token() == VerifyCheck.Token.JUDGE
        check.executorName() == BuiltInExecutors.CLAUDE
        check.checkId() == 'review.md'
        check.settings() == [roundTimeout: '60s', (ExecutorSettings.MODEL_KEY): 'opus']
        check.threshold() == Threshold.DEFAULT
        judges.requests[0].checkRef().label().forLog() == 'judge:review.md'
    }

    // FR4: a judge whose request did not produce a verdict is cannot-verify, and the engine says so
    //     once at WARN — an infrastructure failure and an answer that is no verdict alike
    // Scenario: observability/factory-logging / A vote that cannot be cast says so
    def "a judge that cannot verify (#situation) is logged once with its reason and details"() {
        given:
        def logs = LogCaptureSupport.attach(VerifyOrchestrator)
        def judges = new ScriptedCheckRounds([answer])

        when:
        def result = orchestrator(new ScriptedCheckRounds(), new ScriptedCheckRounds(), new ScriptedCheckRounds(), judges)
                .verify(VerifyingStages.of([judge()]), CONTEXT, WORKSPACE, KEY, TaskState.atStageStart('build'), null)

        then:
        result.results()[0].verdict() instanceof Verdict.CannotVerify

        and: 'one WARN names the check, the reason and the details'
        def warnings = logs.list.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.JUDGE_CANNOT_VERIFY_BY_DECISION.head())
        warnings[0].formattedMessage.contains('judge:criteria.md')
        warnings[0].formattedMessage.contains(reason)
        warnings[0].formattedMessage.contains(details)

        cleanup:
        logs?.detach()

        where:
        situation | answer || reason | details
        'process failed' | new VerdictAnswer.InfrastructureFailure(UntrustedText.factory('the agent CLI failed to start'), UntrustedText.subprocess('no such file')) || 'the agent CLI failed to start' | 'no such file'
        'malformed answer' | new VerdictAnswer.Malformed(UntrustedText.agent('no JSON object')) || 'the verdict answer is malformed' | 'no JSON object'
    }

    // FR4: a judge that passes, or fails, writes no operator line; nor does an executor check that
    //     cannot verify — the line is the judge's
    def "no WARN for a judge that reached a verdict, nor for a non-judge check that cannot verify"() {
        given:
        def logs = LogCaptureSupport.attach(VerifyOrchestrator)
        def judges = new ScriptedCheckRounds([
            new Verdict.Pass(null, []),
            failing('red')
        ])
        def commands = new ScriptedCheckRounds([
            new Verdict.CannotVerify(UntrustedText.subprocess('binary not found'), UntrustedText.subprocess('-'))
        ])

        when:
        def chain = orchestrator(new ScriptedCheckRounds(), commands, new ScriptedCheckRounds(), judges)
        chain.verify(VerifyingStages.of([judge(), judge()]), CONTEXT, WORKSPACE, KEY, TaskState.atStageStart('build'), null)
        chain.verify(VerifyingStages.of([command('missing')]), CONTEXT, WORKSPACE, KEY, TaskState.atStageStart('build'), null)

        then:
        logs.list.findAll { it.level == Level.WARN } == []

        cleanup:
        logs?.detach()
    }

    // NFR-C1: each judge check's request is one verdict entry, its vote the ordinal among the
    //     round's judge checks, its usage as reported; an executor check adds none
    def "each judge check's request becomes one verdict entry carrying its ordinal, its usage as reported"() {
        given: 'two judge checks around a command check: a token-reporting pass, then an unreported fail'
        def first = ['model-a': new TokenUsage(10, 20, 0, 0)]
        def judges = new ScriptedCheckRounds([
            new ExecutionResult(new ExecutionResult.Completed(),
            new ExecutionResult.VerdictReply(new VerdictAnswer.Sarif([], null)),
            new ExecutorUsage(java.time.Duration.ZERO, [], Unpriced.of(first)),
            new ToolTrace(KEY, []), []),
            failing('red')
        ])
        def commands = new ScriptedCheckRounds([new Verdict.Pass(null, [])])

        when:
        def result = orchestrator(new ScriptedCheckRounds(), commands, new ScriptedCheckRounds(), judges)
                .verify(VerifyingStages.of([
                    judge(),
                    command('./gradlew test'),
                    judge()
                ]), CONTEXT, WORKSPACE, KEY,
                TaskState.atStageStart('build'), null)

        then: 'three checks ran, two of them judges, each one verdict entry in request order'
        result.results()*.verdict()*.getClass() == [
            Verdict.Pass,
            Verdict.Pass,
            Verdict.Fail
        ]
        result.votes() == [
            Participants.verdict(BuiltInExecutors.CLAUDE, 0, Unpriced.of(first)),
            Participants.verdict(BuiltInExecutors.CLAUDE, 1, [:])
        ]
        result.votes()*.provenance() == [
            UsageProvenance.REPORTED,
            UsageProvenance.ABSENT
        ]
    }

    // FR7: the TaskContext (with its decisions) reaches the judge's request unmodified
    def "threads the task context through to the judge's request"() {
        given: 'a context carrying a decision and a passing judge'
        def context = new TaskContext('TASK-9', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [
            new Decision('ship it', 'build', 'alice', java.time.Instant.EPOCH)
        ])
        def judges = new ScriptedCheckRounds([new Verdict.Pass(null, [])])

        when: 'the judge check is verified'
        orchestrator(new ScriptedCheckRounds(), new ScriptedCheckRounds(), new ScriptedCheckRounds(), judges)
                .verify(VerifyingStages.of([judge()]), context, WORKSPACE, KEY, TaskState.atStageStart('build'), null)

        then: 'the request carried the exact context the chain was given'
        judges.requests*.context() == [context]
    }
}
