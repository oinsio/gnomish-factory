package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyingStages
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration

/**
 * VerifyOrchestrator routes every executor check's answer through VerdictDerivation with the
 * check's own threshold (FR7, design D5, D9 of migrate-verify-to-executor-contract): the runners
 * answer, the orchestrator derives — so the same answer is a pass under one threshold and a fail
 * under another, and an external check's answer is judged by the same owner.
 */
class VerifyDerivationRoutingSpec extends VerifyOrchestratorSpecBase {

    private static final Finding WARNING = new Finding('unused import', 'src/A.java:3', null)

    private static VerdictAnswer warningAnswer() {
        new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(ResultLevel.WARNING, WARNING)
        ])
    }

    private static VerifyCheck.Executor withThreshold(VerifyCheck.Executor check, Threshold threshold) {
        new VerifyCheck.Executor(check.token(), check.executorName(), check.checkId(), check.settings(),
                check.interval(), check.timeout(), check.timeoutClass(), check.pinPaths(), check.verifyIn(),
                threshold, check.expected())
    }

    // FR7: the builtin runner's answer is judged against the check's threshold
    def "FR7: a builtin answer of one warning is #outcome under threshold #threshold"() {
        given:
        def builtinRunner = new ScriptedCheckRounds([warningAnswer()])
        def check = withThreshold(builtin('files_exist'), threshold)

        when:
        def result = orchestrator(builtinRunner, new ScriptedCheckRounds()).verify(VerifyingStages.of([check]), CONTEXT, WORKSPACE, KEY, TaskState.atStageStart('build'), null)

        then:
        result.results*.verdict() == [verdict]

        where:
        threshold || verdict
        Threshold.ERROR || new Verdict.Pass(null, [WARNING])
        Threshold.WARNING || new Verdict.Fail([WARNING])
        Threshold.NOTE || new Verdict.Fail([WARNING])

        outcome = verdict instanceof Verdict.Pass ? 'a pass' : 'a fail'
    }

    // FR7: the command runner's answer is judged against the check's threshold too
    def "FR7: a command answer of one warning fails a check whose threshold is warning"() {
        given:
        def commandRunner = new ScriptedCheckRounds([warningAnswer()])
        def check = withThreshold(command('./lint'), Threshold.WARNING)

        expect:
        orchestrator(new ScriptedCheckRounds(), commandRunner)
                .verify(VerifyingStages.of([check]), CONTEXT, WORKSPACE, KEY, TaskState.atStageStart('build'), null).results*.verdict() == [new Verdict.Fail([WARNING])]
    }

    // FR7: a failed status from a runner derives through the same owner, cannot-verify included
    def "FR7: a command runner's infrastructure failure is cannot-verify and stops the chain"() {
        given:
        def reason = UntrustedText.factory('command not found (exit 127)')
        def details = UntrustedText.subprocess('sh: lint: not found')
        def commandRunner = new ScriptedCheckRounds([
            new VerdictAnswer.InfrastructureFailure(reason, details)
        ])
        def builtinRunner = new ScriptedCheckRounds()

        when:
        def result = orchestrator(builtinRunner, commandRunner)
                .verify(VerifyingStages.of([
                    command('./lint'),
                    builtin('files_exist')
                ]), CONTEXT, WORKSPACE, KEY, TaskState.atStageStart('build'), null)

        then:
        result.results*.verdict() == [
            new Verdict.CannotVerify(reason, details)
        ]
        builtinRunner.calls.isEmpty()
    }

    // FR7 (NFR-O2 of add-sandbox-core): an external pass reaches the record with its run URL through the derivation
    def "FR7: an external pass keeps its platform run URL through the derivation"() {
        given:
        def client = new ScriptedCheckRounds([
            new Verdict.Pass('https://ci.example/runs/9', [])
        ])
        def check = external('ci.yml', Duration.ofSeconds(5), Duration.ofMinutes(1))

        expect:
        orchestrator(new ScriptedCheckRounds(), new ScriptedCheckRounds(), client)
                .verify(VerifyingStages.of([check]), CONTEXT, WORKSPACE, KEY, TaskState.atStageStart('build'), null).results*.verdict() == [
                    new Verdict.Pass('https://ci.example/runs/9', [])
                ]
    }

    // FR7: an external fail without findings stays a fail, never read as an empty, passing answer
    def "FR7: an external fail with no findings is a fail under every threshold"() {
        given:
        def client = new ScriptedCheckRounds([new Verdict.Fail([])])
        def check = withThreshold(external('ci.yml', Duration.ofSeconds(5), Duration.ofMinutes(1)), threshold)

        expect:
        orchestrator(new ScriptedCheckRounds(), new ScriptedCheckRounds(), client)
                .verify(VerifyingStages.of([check]), CONTEXT, WORKSPACE, KEY, TaskState.atStageStart('build'), null).results*.verdict() == [new Verdict.Fail([])]

        where:
        threshold << Threshold.values()
    }
}
