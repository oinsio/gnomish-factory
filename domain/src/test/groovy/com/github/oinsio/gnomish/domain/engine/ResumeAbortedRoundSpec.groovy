package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode

/**
 * The resume matrix — aborted-round resume, task 5.9 (M3): when a round's persist THROWS the
 * run aborts at that round, and the last DURABLY persisted state is the one before it. A NEW
 * run from that state re-executes the unpersisted round, so the lost round was safe to lose
 * and the task proceeds as if the aborted round never happened. Implements FR9, NFR-R4 of
 * add-stage-engine.
 */
class ResumeAbortedRoundSpec extends ResumeMatrixSpecBase {

    // NFR-R4 (aborted-round resume): round 0 persists (a quality failure), round 1's persist
    //     THROWS -> Aborted at round 1. The last DURABLY persisted state is the one after
    //     round 0. A NEW run from it RE-EXECUTES round 1 (executor called again for attempt
    //     index 1) and the task proceeds as if the aborted round never happened.
    def "a resume from the last persisted state re-executes the unpersisted round"() {
        given: 'a high-limit stage that fails on round 0 then cannot verify on round 1, persist aborting on round 1'
        def stageDef = stage('build', AdvancementMode.AUTO, 9, [builtin('files_exist')])
        def priorBuiltin = new ScriptedBuiltinCheckRunner()
        priorBuiltin.scripted << fail('findingA')
        priorBuiltin.scripted << new Verdict.CannotVerify('binary not found', 'no such tool')
        def priorPersistence = new InMemoryAttemptPersistence()
        priorPersistence.failOnCall = 2 // round 0 persists; round 1's persist throws
        def prior = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE,
                freshPorts(new ScriptedExecutor([completed(), completed()]), priorBuiltin,
                priorPersistence, new RecordingEventListener()))

        expect: 'the prior run aborted on round 1, and the last DURABLE state is the one after round 0'
        prior instanceof TaskOutcome.Aborted
        (prior as TaskOutcome.Aborted).failedAt() == new AttemptKey('TASK-1', 'build', 1)
        def lastPersisted = priorPersistence.entries[0].state // round 0 persisted before round 1's persist threw
        lastPersisted.attempts().size() == 1
        lastPersisted.attemptsUsed() == 1

        when: 'a NEW run resumes from the last durably persisted state; round 1 now passes'
        def resumeExecutor = new ScriptedExecutor([completed()])
        def resumeBuiltin = new ScriptedBuiltinCheckRunner()
        resumeBuiltin.scripted << new Verdict.Pass()
        def resumed = new Engine().run(pipeline(stageDef), CONTEXT, lastPersisted, WORKSPACE,
                freshPorts(resumeExecutor, resumeBuiltin, new InMemoryAttemptPersistence(), new RecordingEventListener()))

        then: 'round 1 was RE-EXECUTED — the executor was called again at attempt index 1'
        resumeExecutor.requests.size() == 1
        resumeExecutor.requests[0].attempt() == 1

        and: 'the task proceeds to completion as if the aborted round never happened'
        resumed instanceof TaskOutcome.Completed
        resumed.finalState().position() instanceof Position.PipelineEnd
    }
}
