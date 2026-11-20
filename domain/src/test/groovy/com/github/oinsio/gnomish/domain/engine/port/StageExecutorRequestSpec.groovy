package com.github.oinsio.gnomish.domain.engine.port

import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * StageExecutor.Request: the inputs of one execution round — the task context,
 * the stage, the opaque workspace, the zero-based attempt number, and the
 * feedback (failed check results of all prior attempts). Feedback is defensively
 * copied and unmodifiable; a negative attempt is rejected. Implements FR1, D2 of
 * add-stage-engine.
 */
class StageExecutorRequestSpec extends Specification {

    private static TaskContext sampleContext() {
        new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    }

    private static StageDefinition sampleStage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static Workspace sampleWorkspace() {
        new Workspace() {}
    }

    private static CheckResult sampleFeedback(String label) {
        new CheckResult(new CheckRef(0, UntrustedText.manifest(label)), new Verdict.Fail([]), Duration.ZERO)
    }

    // FR1: construction exposes every component exactly as supplied
    def "a Request exposes all components as constructed"() {
        given: 'the round inputs'
        def context = sampleContext()
        def stage = sampleStage()
        def workspace = sampleWorkspace()
        def feedback = [
            sampleFeedback('command:./gradlew test')
        ]

        when: 'a Request is created'
        def request = new StageExecutor.Request(context, stage, workspace, 2, feedback)

        then: 'each component is exposed exactly as constructed'
        request.context() == context
        request.stage() == stage
        request.workspace().is(workspace)
        request.attempt() == 2
        request.feedback() == feedback
    }

    // FR1: the first attempt carries no prior feedback — an empty list is valid
    def "a Request accepts an empty feedback list"() {
        when: 'a Request is created with no prior feedback'
        def request = new StageExecutor.Request(
                sampleContext(), sampleStage(), sampleWorkspace(), 0, [])

        then: 'the feedback list is empty'
        request.feedback().isEmpty()
    }

    // FR1: feedback is copied on construction — later source mutation cannot leak in
    def "the feedback list is defensively copied from the source"() {
        given: 'a mutable source list'
        def source = [
            sampleFeedback('builtin:files_exist')
        ]

        when: 'a Request is created and the source is then mutated'
        def request = new StageExecutor.Request(
                sampleContext(), sampleStage(), sampleWorkspace(), 1, source)
        source.add(sampleFeedback('command:sneaked'))

        then: 'the request keeps its original single feedback entry'
        request.feedback() == [
            sampleFeedback('builtin:files_exist')
        ]
    }

    // FR1: the exposed feedback list is unmodifiable — the engine carries it verbatim
    def "the exposed feedback list is unmodifiable"() {
        given: 'a Request'
        def request = new StageExecutor.Request(
                sampleContext(), sampleStage(), sampleWorkspace(), 0,
                [
                    sampleFeedback('builtin:files_exist')
                ])

        when: 'a caller tries to add a feedback entry'
        request.feedback().add(sampleFeedback('command:extra'))

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR1: the attempt number cannot be negative
    def "a Request rejects a negative attempt with the component named"() {
        when: 'a Request is created with a negative attempt'
        new StageExecutor.Request(
                sampleContext(), sampleStage(), sampleWorkspace(), attempt, [])

        then: 'construction fails and the message names the negative component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('Request.attempt')

        where:
        attempt << [-1, -2, Integer.MIN_VALUE]
    }

    // FR1: a zero attempt is valid — the first round is attempt 0
    def "a Request accepts a zero attempt"() {
        when: 'a Request is created for the first round'
        def request = new StageExecutor.Request(
                sampleContext(), sampleStage(), sampleWorkspace(), 0, [])

        then: 'the attempt is zero'
        request.attempt() == 0
    }

    // FR1: Requests are values — equal content means equal requests
    def "Requests with the same components are equal values"() {
        given: 'shared components'
        def context = sampleContext()
        def stage = sampleStage()
        def workspace = sampleWorkspace()
        def feedback = [
            sampleFeedback('command:./gradlew test')
        ]

        expect: 'two independently constructed Requests with equal content are equal'
        new StageExecutor.Request(context, stage, workspace, 1, feedback) ==
                new StageExecutor.Request(context, stage, workspace, 1, feedback)

        and: 'a differing attempt makes them unequal'
        new StageExecutor.Request(context, stage, workspace, 1, feedback) !=
                new StageExecutor.Request(context, stage, workspace, 2, feedback)
    }

    // FR1, D18 of migrate-verify-to-executor-contract: a stage round has no check subject and no turn
    def "a stage round's Request names no check and no pending turn"() {
        expect:
        with(new StageExecutor.Request(sampleContext(), sampleStage(), sampleWorkspace(), 0, [])) {
            check() == null
            pending() == null
        }
    }

    // FR1, FR2, D14, D18: a check round names its check beside the stage, and may carry the turn it continues
    def "a check round's Request carries its check and its pending turn"() {
        given:
        def check = VerifyChecks.builtin('files', [files: ['a.txt']])
        def turn = new PendingTurn(new PendingKey('job-9'), PendingTurn.Origin.ISSUED)

        when:
        def request = new StageExecutor.Request(sampleContext(), sampleStage(), sampleWorkspace(), 1, [], check,
        CheckRef.of(2, check), turn)

        then:
        request.check() == check
        request.checkRef() == CheckRef.of(2, check)
        request.pending() == turn
        request.stage() == sampleStage()
    }

    // FR3, D14 as amended 2026-11-13: the check and its identity in the round are set together or not at all
    def "a Request with #why is refused naming both components"() {
        when:
        new StageExecutor.Request(sampleContext(), sampleStage(), sampleWorkspace(), 0, [], check, checkRef, null)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == 'StageExecutor.Request.check and checkRef are set together or not at all'

        where:
        why | check | checkRef
        'a check and no identity' | VerifyChecks.builtin('files', [files: ['a.txt']]) | null
        'an identity and no check' | null | CheckRef.of(0, VerifyChecks.builtin('files', [files: ['a.txt']]))
    }

    // FR3, D14 as amended 2026-11-13: a stage round carries neither
    def "a stage round's Request carries no check identity"() {
        expect:
        new StageExecutor.Request(sampleContext(), sampleStage(), sampleWorkspace(), 0, []).checkRef() == null
    }

    // D14: the turn's two origins
    def "a pending turn is issued by this run or recorded on resume"() {
        expect:
        PendingTurn.Origin.values()*.name() == ['ISSUED', 'RECORDED']
    }
}
