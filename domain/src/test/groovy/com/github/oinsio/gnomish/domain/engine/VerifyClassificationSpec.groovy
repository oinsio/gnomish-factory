package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyingStages
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration

/**
 * The normative Fail/CannotVerify classification table (design.md "Failure
 * classification (normative, D3)" / spec.md "Failure classification"), verified
 * row-by-row by a single data-driven feature. Each row scripts a fake port to
 * produce exactly one table situation and asserts the {@link Verdict} class the
 * engine collapses it to — proving the engine draws the Fail vs CannotVerify line
 * where the table draws it. Implements FR4 and success metric M4 of add-stage-engine.
 */
class VerifyClassificationSpec extends VerifyOrchestratorSpecBase {

    // FR4, M4: every line of the normative classification table classifies exactly as
    //          the table says — the engine collapses each situation to the expected
    //          Verdict class (Fail = quality failure, CannotVerify = infrastructure).
    // Scenario: pipeline/stage-engine / Classification table verified
    // Scenario: pipeline/stage-engine / A command check classifies by how it ended
    // Scenario: pipeline/stage-engine / An external check classifies by its poll
    // Scenario: pipeline/stage-engine / A judge check classifies by its votes
    def "classifies each normative table row as its table class: #situation"() {
        given: 'the orchestrator built over the scripted fakes for this row'
        def orchestrator = orchestrator(builtinRunner, commandRunner, externalClient, judges)

        when: 'the single-check verify list for this row is verified'
        def result = orchestrator.verify(VerifyingStages.of([check] as List<VerifyCheck>), CONTEXT, WORKSPACE, KEY, TaskState.atStageStart('build'), null)

        then: 'the last (and only) check verdict is of the class the table prescribes'
        def verdict = result.results().last().verdict()
        expectedClass.isInstance(verdict)

        where: 'one row per line of the normative Fail/CannotVerify table (M4)'
        situation | check | builtinRunner | commandRunner | externalClient | judges || expectedClass
        // M4 row 1 — command exit code != 0 -> Fail (quality)
        'command exit code != 0' | command('./gradlew test') | new ScriptedCheckRounds() | new ScriptedCheckRounds([
            new Verdict.Fail([
                new Finding('exit 1', null, null)
            ])
        ]) | new ScriptedCheckRounds() | new ScriptedCheckRounds() || Verdict.Fail
        // M4 row 2 — command binary not found / cannot start -> CannotVerify
        'command binary not found' | command('missing-bin') | new ScriptedCheckRounds() | new ScriptedCheckRounds([
            new Verdict.CannotVerify(UntrustedText.subprocess('binary not found'), UntrustedText.subprocess('no such file'))
        ]) | new ScriptedCheckRounds() | new ScriptedCheckRounds() || Verdict.CannotVerify
        // M4 row 3 — external check's executor answers failure -> Fail (quality)
        'external poll returns failure' | external('ci/gate', SEC, TIMEOUT) | new ScriptedCheckRounds() | new ScriptedCheckRounds() | new ScriptedCheckRounds([
            new Verdict.Fail([
                new Finding('gate red', null, null)
            ])
        ]) | new ScriptedCheckRounds() || Verdict.Fail
        // M4 row 4 — external check still pending at its timeout -> Fail (quality, the default class)
        'external poll timeout elapsed' | external('ci/slow', SEC, TIMEOUT) | new ScriptedCheckRounds() | new ScriptedCheckRounds() | new ScriptedCheckRounds([
            RUNNING,
            RUNNING,
            RUNNING,
            RUNNING
        ]) | new ScriptedCheckRounds() || Verdict.Fail
        // M4 row 5 — external check id unknown to the service -> CannotVerify
        'external check id unknown' | external('ci/missing', SEC, TIMEOUT) | new ScriptedCheckRounds() | new ScriptedCheckRounds() | new ScriptedCheckRounds([
            new Verdict.CannotVerify(UntrustedText.tracker('check id unknown'), UntrustedText.tracker('no such check'))
        ]) | new ScriptedCheckRounds() || Verdict.CannotVerify
        // M4 row 6 — judge verdict negative -> Fail (quality); the judge's one verdict request
        //     answers a failing SARIF verdict (design D2 of extract-agent-round-runner)
        'judge verdict negative' | judge() | new ScriptedCheckRounds() | new ScriptedCheckRounds() | new ScriptedCheckRounds() | new ScriptedCheckRounds([
            failingVerdict()
        ]) || Verdict.Fail
        // M4 row 7 — judge model reply unparseable as verdict -> CannotVerify: the answer is no verdict
        'judge reply unparseable' | judge() | new ScriptedCheckRounds() | new ScriptedCheckRounds() | new ScriptedCheckRounds() | new ScriptedCheckRounds([
            new VerdictAnswer.Malformed(UntrustedText.agent('not JSON'))
        ]) || Verdict.CannotVerify
        // M4 row 9 — any check adapter throws -> CannotVerify (caught, stack trace kept)
        'check adapter throws' | command('./gradlew test') | new ScriptedCheckRounds() | throwingCommandRunner() | new ScriptedCheckRounds() | new ScriptedCheckRounds() || Verdict.CannotVerify
    }

    // --- fixtures -----------------------------------------------------------------

    static final Duration SEC = Duration.ofSeconds(1)
    static final Duration TIMEOUT = Duration.ofSeconds(3)
    static final ExecutionResult.Pending RUNNING = ScriptedCheckRounds.pending()

    static VerdictAnswer failingVerdict() {
        new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(ResultLevel.ERROR, new Finding('criteria not met', null, null))
        ], null)
    }

    static ScriptedCheckRounds throwingCommandRunner() {
        def runner = new ScriptedCheckRounds()
        runner.toThrow = new RuntimeException('command adapter kaboom')
        runner
    }
}
