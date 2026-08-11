package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
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
    def "classifies each normative table row as its table class: #situation"() {
        given: 'the orchestrator built over the scripted fakes for this row'
        def orchestrator = orchestrator(builtinRunner, commandRunner, externalClient, judgeVoter)

        when: 'the single-check verify list for this row is verified'
        def result = orchestrator.verify([check] as List<VerifyCheck>, CONTEXT, WORKSPACE, KEY)

        then: 'the last (and only) check verdict is of the class the table prescribes'
        def verdict = result.results().last().verdict()
        expectedClass.isInstance(verdict)

        where: 'one row per line of the normative Fail/CannotVerify table (M4)'
        situation                                     | check                                         | builtinRunner                    | commandRunner                                                                                                        | externalClient                                                                                          | judgeVoter                                                                                                                                                             || expectedClass
        // M4 row 1 — command exit code != 0 -> Fail (quality)
        'command exit code != 0'                      | command('./gradlew test')                     | new ScriptedBuiltinCheckRunner() | new ScriptedCommandCheckRunner([
            new Verdict.Fail([
                new Finding('exit 1', null, null)
            ])
        ])                               | new ScriptedExternalCheckClient()                                                                       | new ScriptedJudgeVoter()                                                                                                                                                || Verdict.Fail
        // M4 row 2 — command binary not found / cannot start -> CannotVerify
        'command binary not found'                    | command('missing-bin')                        | new ScriptedBuiltinCheckRunner() | new ScriptedCommandCheckRunner([
            new Verdict.CannotVerify('binary not found', 'no such file')
        ])                        | new ScriptedExternalCheckClient()                                                                       | new ScriptedJudgeVoter()                                                                                                                                                || Verdict.CannotVerify
        // M4 row 3 — external poll returns failure -> Fail (quality)
        'external poll returns failure'               | external('ci/gate', SEC, TIMEOUT)             | new ScriptedBuiltinCheckRunner() | new ScriptedCommandCheckRunner()                                                                                     | new ScriptedExternalCheckClient([
            new PollStatus.Fail([
                new Finding('gate red', null, null)
            ])
        ])           | new ScriptedJudgeVoter()                                                                                                                                                || Verdict.Fail
        // M4 row 4 — external poll timeout elapsed -> Fail (quality, hardcoded default)
        'external poll timeout elapsed'               | external('ci/slow', SEC, TIMEOUT)             | new ScriptedBuiltinCheckRunner() | new ScriptedCommandCheckRunner()                                                                                     | new ScriptedExternalCheckClient([
            RUNNING,
            RUNNING,
            RUNNING,
            RUNNING
        ])                                   | new ScriptedJudgeVoter()                                                                                                                                                || Verdict.Fail
        // M4 row 5 — external check id unknown to the service -> CannotVerify
        'external check id unknown'                   | external('ci/missing', SEC, TIMEOUT)          | new ScriptedBuiltinCheckRunner() | new ScriptedCommandCheckRunner()                                                                                     | new ScriptedExternalCheckClient([
            new PollStatus.CannotVerify('check id unknown', 'no such check')
        ])     | new ScriptedJudgeVoter()                                                                                                                                                || Verdict.CannotVerify
        // M4 row 6 — judge majority of votes negative -> Fail (quality)
        'judge majority negative'                     | judge(3)                                      | new ScriptedBuiltinCheckRunner() | new ScriptedCommandCheckRunner()                                                                                     | new ScriptedExternalCheckClient()                                                                       | new ScriptedJudgeVoter([
            failVote(),
            passVote(),
            failVote()
        ])                                                                                                            || Verdict.Fail
        // M4 row 7 — judge model reply unparseable as verdict -> CannotVerify
        'judge reply unparseable'                     | judge(3)                                      | new ScriptedBuiltinCheckRunner() | new ScriptedCommandCheckRunner()                                                                                     | new ScriptedExternalCheckClient()                                                                       | new ScriptedJudgeVoter([
            new JudgeVoter.Vote(new Verdict.CannotVerify('unparseable verdict', 'not JSON'), [:])
        ])                                                        || Verdict.CannotVerify
        // M4 row 8 — judge any single vote CannotVerify -> CannotVerify (whole check)
        'judge any single vote CannotVerify'          | judge(3)                                      | new ScriptedBuiltinCheckRunner() | new ScriptedCommandCheckRunner()                                                                                     | new ScriptedExternalCheckClient()                                                                       | new ScriptedJudgeVoter([
            passVote(),
            new JudgeVoter.Vote(new Verdict.CannotVerify('service down', 'timeout'), [:]),
            passVote()
        ])                                        || Verdict.CannotVerify
        // M4 row 9 — any check adapter throws -> CannotVerify (caught, stack trace kept)
        'check adapter throws'                        | command('./gradlew test')                     | new ScriptedBuiltinCheckRunner() | throwingCommandRunner()                                                                                              | new ScriptedExternalCheckClient()                                                                       | new ScriptedJudgeVoter()                                                                                                                                                || Verdict.CannotVerify
    }

    // --- fixtures -----------------------------------------------------------------

    static final Duration SEC = Duration.ofSeconds(1)
    static final Duration TIMEOUT = Duration.ofSeconds(3)
    static final PollStatus RUNNING = new PollStatus.Running()

    static JudgeVoter.Vote passVote() {
        new JudgeVoter.Vote(new Verdict.Pass(), [:])
    }

    static JudgeVoter.Vote failVote() {
        new JudgeVoter.Vote(new Verdict.Fail([]), [:])
    }

    static ScriptedCommandCheckRunner throwingCommandRunner() {
        def runner = new ScriptedCommandCheckRunner()
        runner.toThrow = new RuntimeException('command adapter kaboom')
        runner
    }
}
