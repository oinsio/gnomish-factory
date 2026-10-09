package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The {@code state.json} position vocabulary: every {@link Position} variant survives the write and
 * the read through the JSON document, iterated over the sealed type's permitted subclasses so a new
 * variant mapped on one side only fails here (testing.md, "Every wire vocabulary has a round-trip
 * spec"); the gate's token is {@code awaitingApproval} with its {@code stage}, version 1.
 *
 * <p>FR10 of make-checkpoint-gate-durable (design D5).
 */
class StatePositionWireSpec extends Specification {

    private static final Map<Class, Position> SAMPLES = [
        (Position.AtStage) : new Position.AtStage('implement'),
        (Position.AwaitingApproval): new Position.AwaitingApproval('release'),
        (Position.PipelineEnd) : new Position.PipelineEnd(),
    ]

    private static String write(Position position) {
        TaskStateJson.mapper().writeValueAsString(
                StateJsonMapper.toDto(new TaskState(position, 0, [], ExecutorUsage.none())))
    }

    // FR10: no hand-listed subset — every permitted subclass has a sample and comes back equal
    def "the #variant.simpleName position survives the state.json write and read"() {
        given:
        def position = SAMPLES[variant]

        when:
        def read = StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(write(position))))

        then:
        position != null
        read.position() == position

        where:
        variant << (Position.getPermittedSubclasses() as List)
    }

    // FR10, D5: the gate is its own token carrying the stage, and the document stays version 1
    def "a gate is written as awaitingApproval with its stage, at version 1"() {
        when:
        def json = TaskStateJson.mapper().readTree(write(new Position.AwaitingApproval('release')))

        then:
        json.get('version').asInt() == 1
        json.get('position').get('type').asText() == 'awaitingApproval'
        json.get('position').get('stage').asText() == 'release'
    }
}
