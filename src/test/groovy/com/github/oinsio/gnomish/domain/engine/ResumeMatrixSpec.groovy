package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode

/**
 * The resume matrix — valid-state resumes, task 5.9 (M3): the engine resumes correctly at
 * attempt-boundary granularity from any valid recorded {@code TaskState}. This spec covers
 * the resume shapes that start from a valid recorded state — mid-pipeline (FR9), mid-retry
 * continuing the attempt counter and feedback (FR9, FR4), post-pause starting at the next
 * stage (FR9, FR8), and a resume after a non-burning {@code CannotVerify} round whose result
 * still flows into the next feedback (FR9, FR4) — each, where practical, obtained from a REAL
 * prior run's persisted state rather than hand-built. Implements FR9, FR4 of add-stage-engine.
 */
class ResumeMatrixSpec extends ResumeMatrixSpecBase {

    // FR9 (mid-pipeline resume): a run started from a state positioned at stage2 of a
    //     3-stage AUTO pipeline — as if stage1 completed in an earlier run — executes only
    //     stage2 then stage3 and completes; the executor is NEVER invoked for stage1.
    def "a run starting mid-pipeline executes only the remaining stages"() {
        given: 'a 3-stage auto pipeline, each stage with its own passing builtin check'
        def stage1 = stage('build', AdvancementMode.AUTO, 3, [builtin('build_check')])
        def stage2 = stage('test', AdvancementMode.AUTO, 3, [builtin('test_check')])
        def stage3 = stage('review', AdvancementMode.AUTO, 3, [builtin('review_check')])
        def executor = new ScriptedExecutor([completed(), completed()])
        def builtin = new ScriptedBuiltinCheckRunner()
        builtin.scripted << new Verdict.Pass()
        builtin.scripted << new Verdict.Pass()

        when: 'the run starts from a state positioned at stage2 (stage1 already done in an earlier run)'
        def outcome = new Engine().run(pipeline(stage1, stage2, stage3), CONTEXT,
                TaskState.atStageStart('test'), WORKSPACE,
                freshPorts(executor, builtin, new InMemoryAttemptPersistence(), new RecordingEventListener()))

        then: 'the run completes at the pipeline end'
        outcome instanceof TaskOutcome.Completed
        outcome.finalState().position() instanceof Position.PipelineEnd

        and: 'the executor was invoked only for stage2 and stage3 — never for stage1'
        executor.requests.collect { it.stage().name() } == ['test', 'review']
    }

    // FR9 (mid-retry resume): a state recorded after two quality failures (attemptsUsed == 2,
    //     two recorded failed rounds) — obtained from a REAL prior run whose 3rd-round persist
    //     is aborted — resumes at attempt 2 with BOTH prior failures still in feedback, then
    //     terminates deterministically. The counter and feedback continue across the resume.
    def "a mid-retry resume continues the attempt counter and feedback"() {
        given: 'a high-limit stage that fails twice then aborts persistence on the third round'
        def stageDef = stage('build', AdvancementMode.AUTO, 9, [builtin('files_exist')])
        def failA = fail('findingA')
        def failB = fail('findingB')
        def priorBuiltin = new ScriptedBuiltinCheckRunner()
        priorBuiltin.scripted << failA
        priorBuiltin.scripted << failB
        priorBuiltin.scripted << fail('findingC')
        def priorPersistence = new InMemoryAttemptPersistence()
        priorPersistence.failOnCall = 3
        def priorPorts = freshPorts(new ScriptedExecutor([
            completed(),
            completed(),
            completed()
        ]),
        priorBuiltin, priorPersistence, new RecordingEventListener())

        and: 'a prior run reaches a mid-retry state persisted after the SECOND quality failure'
        def prior = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, priorPorts)
        prior instanceof TaskOutcome.Aborted
        def resumeState = priorPersistence.entries[1].state // durable state after round 1 (two failures)
        resumeState.attemptsUsed() == 2
        resumeState.attempts().size() == 2

        when: 'a NEW run resumes from that persisted mid-retry state and its check now passes'
        def resumeExecutor = new ScriptedExecutor([completed()])
        def resumeBuiltin = new ScriptedBuiltinCheckRunner()
        resumeBuiltin.scripted << new Verdict.Pass()
        def resumed = new Engine().run(pipeline(stageDef), CONTEXT, resumeState, WORKSPACE,
                freshPorts(resumeExecutor, resumeBuiltin, new InMemoryAttemptPersistence(), new RecordingEventListener()))

        then: 'the resumed run reaches attempt 2 first — the counter continued across the resume'
        resumeExecutor.requests[0].attempt() == 2

        and: 'that first request carries the failed check results of BOTH prior attempts, in order'
        def feedback = resumeExecutor.requests[0].feedback()
        feedback.size() == 2
        feedback[0].verdict().is(failA)
        feedback[1].verdict().is(failB)

        and: 'the now-passing round advances the pipeline to completion'
        resumed instanceof TaskOutcome.Completed
    }

    // FR9/FR8 (post-pause resume): a two-stage pipeline whose first stage is MANUAL pauses
    //     with the position already advanced to stage2; a SECOND run from that Paused final
    //     state starts at stage2 (never re-running stage1) and completes.
    def "a post-pause resume starts at the next stage"() {
        given: 'a two-stage pipeline whose first stage is a manual checkpoint'
        def stage1 = stage('build', AdvancementMode.MANUAL, 3, [])
        def stage2 = stage('test', AdvancementMode.AUTO, 3, [])

        and: 'a first run pauses on the manual stage with the position advanced to stage2'
        def paused = new Engine().run(pipeline(stage1, stage2), CONTEXT, TaskState.atStageStart('build'), WORKSPACE,
                freshPorts(new ScriptedExecutor([completed()]), new ScriptedBuiltinCheckRunner(),
                new InMemoryAttemptPersistence(), new RecordingEventListener()))
        paused instanceof TaskOutcome.Paused
        paused.finalState().position() == new Position.AtStage('test')

        when: 'a SECOND run resumes from the paused final state'
        def resumeExecutor = new ScriptedExecutor([completed()])
        def resumed = new Engine().run(pipeline(stage1, stage2), CONTEXT, paused.finalState(), WORKSPACE,
                freshPorts(resumeExecutor, new ScriptedBuiltinCheckRunner(),
                new InMemoryAttemptPersistence(), new RecordingEventListener()))

        then: 'the resume ran only stage2 and completed — stage1 was never re-executed'
        resumed instanceof TaskOutcome.Completed
        resumeExecutor.requests.collect { it.stage().name() } == ['test']
    }

    // FR4 (feedback carries every non-Pass result of prior attempts, INCLUDING CannotVerify):
    //     a CannotVerify round is recorded unburned and escalates immediately, so its result can
    //     only reach a later executor request across a resume. A prior run leaves a persisted
    //     CannotVerify round (attemptsUsed unchanged); a resume from that state feeds that
    //     CannotVerify check result into the first executor request — proving the "including
    //     CannotVerify" clause of FR4, which no single-run feedback test can reach.
    def "a resume after a CannotVerify round carries that result into the next feedback"() {
        given: 'a stage whose first round cannot verify — recorded unburned, then escalated'
        def stageDef = stage('build', AdvancementMode.AUTO, 9, [builtin('files_exist')])
        def cannotVerify = new Verdict.CannotVerify('binary not found', 'no such tool')
        def priorBuiltin = new ScriptedBuiltinCheckRunner()
        priorBuiltin.scripted << cannotVerify
        def priorPersistence = new InMemoryAttemptPersistence()
        def priorPorts = freshPorts(new ScriptedExecutor([completed()]),
        priorBuiltin, priorPersistence, new RecordingEventListener())

        and: 'a prior run escalates CannotVerify with the round recorded unburned and persisted'
        def prior = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, priorPorts)
        prior instanceof TaskOutcome.Escalated
        prior.report() instanceof EscalationReport.CannotVerify
        def resumeState = priorPersistence.entries[0].state // durable state after the CannotVerify round
        resumeState.attemptsUsed() == 0
        resumeState.attempts().size() == 1

        when: 'a NEW run resumes from that persisted state and its check now passes'
        def resumeExecutor = new ScriptedExecutor([completed()])
        def resumeBuiltin = new ScriptedBuiltinCheckRunner()
        resumeBuiltin.scripted << new Verdict.Pass()
        def resumed = new Engine().run(pipeline(stageDef), CONTEXT, resumeState, WORKSPACE,
                freshPorts(resumeExecutor, resumeBuiltin, new InMemoryAttemptPersistence(), new RecordingEventListener()))

        then: 'the first resumed request carries the prior CannotVerify check result as feedback'
        def feedback = resumeExecutor.requests[0].feedback()
        feedback.size() == 1
        feedback[0].verdict().is(cannotVerify)

        and: 'the now-passing round advances the pipeline to completion'
        resumed instanceof TaskOutcome.Completed
    }
}
