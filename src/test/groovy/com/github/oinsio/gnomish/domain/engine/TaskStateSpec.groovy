package com.github.oinsio.gnomish.domain.engine

import java.time.Duration
import spock.lang.Specification

/**
 * TaskState: the immutable per-round state of a task — its sealed {@code position},
 * the {@code attemptsUsed} quality failures burned in the current stage, and the
 * {@code attempts} history of all executed rounds of the current stage (design D4).
 * Transition factories build a fresh state and never mutate {@code this}; history
 * resets on advancement (FR14). Implements FR8, FR13, FR14 of add-stage-engine.
 */
class TaskStateSpec extends Specification {

    private static AttemptRecord round(int n) {
        new AttemptRecord(n, AttemptRecord.Result.PASSED, [], ExecutorUsage.none(), JudgeUsage.none())
    }

    private static AttemptRecord round(int n, ExecutorUsage usage) {
        new AttemptRecord(n, AttemptRecord.Result.PASSED, [], usage, JudgeUsage.none())
    }

    // FR8, FR13, FR14: the starting state parks at a named stage with no history
    def "atStageStart parks at the named stage with zero attempts and empty history"() {
        when: 'a starting state is built for a stage'
        def state = TaskState.atStageStart('build')

        then: 'it is positioned at that stage with nothing recorded yet'
        state.position() == new Position.AtStage('build')
        state.attemptsUsed() == 0
        state.attempts().isEmpty()
    }

    // FR13: a validated attemptsUsed round-trips a specific non-trivial literal
    // (pins requireNonNegative's return against a return-value mutation)
    def "a validated attemptsUsed round-trips the constructed literal"() {
        expect: 'the accessor returns the exact non-zero burn count it was built with'
        new TaskState(new Position.AtStage('s'), 3, [], ExecutorUsage.none()).attemptsUsed() == 3
    }

    // FR13: an unburned round is appended to history without burning an attempt
    def "recordUnburnedRound appends the round and leaves attemptsUsed and position unchanged"() {
        given: 'a starting state'
        def start = TaskState.atStageStart('build')

        when: 'an unburned round is recorded'
        def next = start.recordUnburnedRound(round(0))

        then: 'the round is appended, the burn count is untouched, the position is held'
        next.attempts() == [round(0)]
        next.attemptsUsed() == 0
        next.position() == new Position.AtStage('build')
    }

    // FR13: recording an unburned round never mutates the source state
    def "recordUnburnedRound does not mutate the original state"() {
        given: 'a starting state'
        def start = TaskState.atStageStart('build')

        when: 'an unburned round is recorded'
        def next = start.recordUnburnedRound(round(0))

        then: 'a distinct new state is returned and the original is untouched'
        !next.is(start)
        start.attempts().isEmpty()
        start.attemptsUsed() == 0
    }

    // FR13: a quality-failure round is appended AND burns exactly one attempt
    def "recordQualityFailure appends the round and increments attemptsUsed by one"() {
        given: 'a starting state'
        def start = TaskState.atStageStart('build')

        when: 'a quality-failure round is recorded'
        def next = start.recordQualityFailure(round(0))

        then: 'the round is appended, one attempt is burned, the position is held'
        next.attempts() == [round(0)]
        next.attemptsUsed() == 1
        next.position() == new Position.AtStage('build')
    }

    // FR13: recording a quality failure never mutates the source state
    def "recordQualityFailure does not mutate the original state"() {
        given: 'a starting state'
        def start = TaskState.atStageStart('build')

        when: 'a quality-failure round is recorded'
        def next = start.recordQualityFailure(round(0))

        then: 'a distinct new state is returned and the original is untouched'
        !next.is(start)
        start.attempts().isEmpty()
        start.attemptsUsed() == 0
    }

    // FR13, D4: rounds accumulate in order; attempts counts all rounds while
    // attemptsUsed counts only the quality failures (the deliberate divergence)
    def "a mix of unburned and quality-failure rounds diverges attempts from attemptsUsed"() {
        given: 'a starting state'
        def start = TaskState.atStageStart('build')

        when: 'unburned and quality-failure rounds are interleaved'
        def state = start
                .recordUnburnedRound(round(0))
                .recordQualityFailure(round(1))
                .recordUnburnedRound(round(2))
                .recordQualityFailure(round(3))

        then: 'every executed round is recorded, in order'
        state.attempts() == [
            round(0),
            round(1),
            round(2),
            round(3)
        ]
        state.attempts().size() == 4

        and: 'only the two quality failures are burned'
        state.attemptsUsed() == 2
    }

    // FR8, FR14: a normal advance moves to the next stage and resets the history
    def "advanceTo a next stage moves the position and resets attempts and attemptsUsed"() {
        given: 'a state with recorded rounds and a burned attempt'
        def state = TaskState.atStageStart('build')
                .recordQualityFailure(round(0))
                .recordUnburnedRound(round(1))

        when: 'the task advances to the next stage'
        def advanced = state.advanceTo(new Position.AtStage('review'))

        then: 'the position moves and the current-stage history is cleared'
        advanced.position() == new Position.AtStage('review')
        advanced.attempts().isEmpty()
        advanced.attemptsUsed() == 0
    }

    // FR8, FR14: advancing the last stage parks at PipelineEnd and resets history
    def "advanceTo the pipeline end parks at PipelineEnd and resets attempts and attemptsUsed"() {
        given: 'a state with recorded rounds and a burned attempt'
        def state = TaskState.atStageStart('deploy')
                .recordQualityFailure(round(0))

        when: 'the task advances past the last stage'
        def advanced = state.advanceTo(new Position.PipelineEnd())

        then: 'the position is the explicit pipeline end and the history is cleared'
        advanced.position() == new Position.PipelineEnd()
        advanced.attempts().isEmpty()
        advanced.attemptsUsed() == 0
    }

    // FR13: the attempts list is defensively copied from the constructor source
    def "the attempts list is defensively copied from the source"() {
        given: 'a mutable source list'
        def source = [round(0)]

        when: 'a state is created and the source is then mutated'
        def state = new TaskState(new Position.AtStage('build'), 0, source, ExecutorUsage.none())
        source.add(round(1))

        then: 'the state keeps the snapshot taken at construction'
        state.attempts().size() == 1
    }

    // FR13: the exposed attempts list is unmodifiable
    def "the exposed attempts list is unmodifiable"() {
        given: 'a state carrying one round'
        def state = new TaskState(new Position.AtStage('build'), 0, [round(0)], ExecutorUsage.none())

        when: 'a caller tries to mutate the exposed list'
        state.attempts().add(round(1))

        then: 'the mutation is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR13: a burn count cannot be negative — a negative attemptsUsed is rejected
    def "a negative attemptsUsed is rejected with the component named"() {
        when: 'a state is created with a negative burn count'
        new TaskState(new Position.AtStage('build'), attemptsUsed, [], ExecutorUsage.none())

        then: 'construction fails and the message names the component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('TaskState.attemptsUsed')

        where:
        attemptsUsed << [-1, -10, -100]
    }

    // FR8, FR13, FR14: TaskState is inert value data compared by content
    def "task states with the same components are equal values"() {
        expect: 'two independently constructed states with equal components are equal'
        new TaskState(new Position.AtStage('build'), 1, [round(0)], ExecutorUsage.none()) ==
        new TaskState(new Position.AtStage('build'), 1, [round(0)], ExecutorUsage.none())

        and: 'a differing burn count makes them unequal'
        new TaskState(new Position.AtStage('build'), 1, [round(0)], ExecutorUsage.none()) !=
        new TaskState(new Position.AtStage('build'), 2, [round(0)], ExecutorUsage.none())
    }

    private static ExecutorUsage usage(long wallSecs, long tokensIn, long tokensOut, String tool, int calls) {
        new ExecutorUsage(
                Duration.ofSeconds(wallSecs),
                [
                    new ToolUsage(tool, calls, Duration.ofMillis(calls * 10L))
                ],
                new TokenUsage(tokensIn, tokensOut))
    }

    // FR13, NFR-C1, D5: a fresh stage-start state carries the identity totals (nothing recorded yet)
    def "atStageStart totals are the empty ExecutorUsage"() {
        expect: 'the starting totals know nothing'
        TaskState.atStageStart('build').totals() == ExecutorUsage.none()
    }

    // FR13, NFR-C1, D5: an unburned round folds its executor usage into the running totals
    def "recordUnburnedRound folds the round's executor usage into the totals"() {
        given: 'a starting state'
        def start = TaskState.atStageStart('build')

        when: 'an unburned round carrying usage is recorded'
        def next = start.recordUnburnedRound(round(0, usage(5, 100, 20, 'read', 2)))

        then: 'the totals equal that round\'s executor usage'
        next.totals() == usage(5, 100, 20, 'read', 2)
    }

    // FR13, NFR-C1, D5: a quality-failure round also folds its executor usage into the totals
    def "recordQualityFailure folds the round's executor usage into the totals"() {
        given: 'a starting state'
        def start = TaskState.atStageStart('build')

        when: 'a quality-failure round carrying usage is recorded'
        def next = start.recordQualityFailure(round(0, usage(3, 50, 10, 'edit', 1)))

        then: 'the totals equal that round\'s executor usage and one attempt burned'
        next.totals() == usage(3, 50, 10, 'edit', 1)
        next.attemptsUsed() == 1
    }

    // FR13, NFR-C1, D5: totals accumulate the running sum across several recorded rounds
    def "totals accumulate the running sum across a sequence of recorded rounds"() {
        given: 'a starting state'
        def start = TaskState.atStageStart('build')

        when: 'three rounds each report usage on the same and a new tool'
        def state = start
                .recordUnburnedRound(round(0, usage(5, 100, 20, 'read', 2)))
                .recordQualityFailure(round(1, usage(2, 40, 5, 'read', 3)))
                .recordUnburnedRound(round(2, usage(1, 10, 1, 'edit', 4)))

        then: 'wall time and tokens are summed'
        state.totals().wallTime() == Duration.ofSeconds(8)
        state.totals().tokens() == new TokenUsage(150L, 26L)

        and: 'the same tool accumulated (read: 2+3 calls, 20+30ms) and the new tool joined, in order'
        state.totals().tools() == [
            new ToolUsage('read', 5, Duration.ofMillis(50)),
            new ToolUsage('edit', 4, Duration.ofMillis(40))
        ]
    }

    // FR13, NFR-C1, D5: "Cumulative totals survive advancement" — advanceTo resets attempts
    // and attemptsUsed but PRESERVES the whole-task totals accumulated so far
    def "advanceTo resets history but preserves the cumulative totals"() {
        given: 'a state that has accumulated usage over a couple of rounds'
        def state = TaskState.atStageStart('build')
                .recordQualityFailure(round(0, usage(5, 100, 20, 'read', 2)))
                .recordUnburnedRound(round(1, usage(1, 10, 1, 'edit', 4)))
        def totalsBefore = state.totals()

        when: 'the task advances to the next stage'
        def advanced = state.advanceTo(new Position.AtStage('review'))

        then: 'the current-stage history and burn count reset'
        advanced.attempts().isEmpty()
        advanced.attemptsUsed() == 0

        and: 'the cumulative task totals survive the advancement unchanged'
        advanced.totals() == totalsBefore
        advanced.totals().wallTime() == Duration.ofSeconds(6)
        advanced.totals().tokens() == new TokenUsage(110L, 21L)
    }

    // FR13, NFR-C1, D5: totals participate in value equality
    def "totals participate in value equality"() {
        expect: 'states differing only in totals are unequal'
        new TaskState(new Position.AtStage('build'), 0, [], usage(5, 100, 20, 'read', 2)) !=
        new TaskState(new Position.AtStage('build'), 0, [], ExecutorUsage.none())

        and: 'states with equal totals are equal'
        new TaskState(new Position.AtStage('build'), 0, [], usage(5, 100, 20, 'read', 2)) ==
        new TaskState(new Position.AtStage('build'), 0, [], usage(5, 100, 20, 'read', 2))
    }
}
