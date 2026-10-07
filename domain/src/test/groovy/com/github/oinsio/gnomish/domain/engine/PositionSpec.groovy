package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * Position: the sealed location of a task within its pipeline — {@code AtStage(name)},
 * the gate {@code AwaitingApproval(stage)} (FR1 of make-checkpoint-gate-durable), or the
 * explicit {@code PipelineEnd} (design D4). {@code AtStage} names must be
 * non-blank; {@code PipelineEnd} is a component-less end marker, value-equal to any
 * other. Implements FR8 of add-stage-engine.
 */
class PositionSpec extends Specification {

    // FR8: AtStage exposes the stage name it positions the task at
    def "AtStage exposes its stage name as constructed"() {
        when: 'a position at a named stage is created'
        def position = new Position.AtStage('build')

        then: 'the stage name is exposed exactly as constructed'
        position.name() == 'build'
    }

    // FR8: a stage name is required — a blank name is meaningless and rejected
    def "AtStage rejects a blank name with the component named"() {
        when: 'a position is created with a blank stage name'
        new Position.AtStage(name)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('AtStage.name')

        where:
        name << ['', '   ', '\t', ' \n']
    }

    // FR8: AtStage is inert value data compared by content
    def "AtStage positions with the same name are equal values"() {
        expect: 'two independently constructed positions with equal names are equal'
        new Position.AtStage('review') == new Position.AtStage('review')

        and: 'a differing name makes them unequal'
        new Position.AtStage('review') != new Position.AtStage('build')
    }

    // FR1 of make-checkpoint-gate-durable: a gate names the manual stage that passed
    def "AwaitingApproval exposes its stage and compares by it"() {
        expect: 'the stage is exposed as constructed'
        new Position.AwaitingApproval('review').stage() == 'review'

        and: 'gates on the same stage are equal, on another stage unequal, and never an AtStage'
        new Position.AwaitingApproval('review') == new Position.AwaitingApproval('review')
        new Position.AwaitingApproval('review') != new Position.AwaitingApproval('build')
        new Position.AwaitingApproval('review') != new Position.AtStage('review')
    }

    // FR1 of make-checkpoint-gate-durable: a blank gate stage is refused like a blank AtStage name
    def "AwaitingApproval rejects a blank stage with the component named"() {
        when: 'a gate is created with a blank stage'
        new Position.AwaitingApproval(stage)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message == 'Position.AwaitingApproval.stage must not be blank'

        where:
        stage << ['', '   ', '\t', ' \n']
    }

    // FR8: PipelineEnd is a component-less marker — every instance is value-equal
    def "PipelineEnd instances are value-equal"() {
        expect: 'two independently constructed pipeline ends are equal'
        new Position.PipelineEnd() == new Position.PipelineEnd()
    }

    // FR8: Position is sealed — an exhaustive switch handles every variant
    def "an exhaustive switch over Position handles every variant"() {
        expect: 'each variant is matched to its own arm'
        describe(position) == expected

        where:
        position | expected
        new Position.AtStage('build') | 'at build'
        new Position.AwaitingApproval('build') | 'gate build'
        new Position.PipelineEnd() | 'end'
    }

    // FR1 of make-checkpoint-gate-durable: the gate is the third and last permitted variant
    def "Position permits exactly AtStage, AwaitingApproval and PipelineEnd"() {
        expect:
        Position.permittedSubclasses*.simpleName as Set == [
            'AtStage',
            'AwaitingApproval',
            'PipelineEnd'
        ] as Set
    }

    private static String describe(Position position) {
        switch (position) {
            case Position.AtStage: return 'at ' + ((Position.AtStage) position).name()
            case Position.AwaitingApproval: return 'gate ' + ((Position.AwaitingApproval) position).stage()
            case Position.PipelineEnd: return 'end'
            default: throw new IllegalStateException('unreachable')
        }
    }
}
