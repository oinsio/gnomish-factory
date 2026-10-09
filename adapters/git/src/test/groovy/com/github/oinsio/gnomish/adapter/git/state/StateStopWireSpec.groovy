package com.github.oinsio.gnomish.adapter.git.state

import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * The {@code state.json} attempt {@code stop} vocabulary: every {@link Stop} variant survives the
 * write and the read through the JSON document, iterated over the sealed type's permitted
 * subclasses so a variant mapped on one side only fails here (testing.md, "Every wire vocabulary
 * has a round-trip spec"); an absent {@code stop} reads as none, and an unknown {@code stop.type}
 * makes the document unreadable rather than a round without a stop.
 *
 * <p>FR5, FR10 of make-checkpoint-gate-durable (design D3, D5).
 */
class StateStopWireSpec extends Specification {

    private static final Instant STARTED_AT = Instant.parse('2026-10-07T09:00:00Z')

    /** Each variant with the round result it belongs to; the record refuses any other pairing. */
    private static final Map<Class, List> SAMPLES = [
        (Stop.None) : [
            AttemptRecord.Result.QUALITY_FAILURE,
            Stop.none()
        ],
        (Stop.DecisionNeeded): [
            AttemptRecord.Result.DECISION_NEEDED,
            new Stop.DecisionNeeded(UntrustedText.branchDocument('Ship it?'),
            [
                UntrustedText.branchDocument('yes'),
                UntrustedText.branchDocument('no')
            ])
        ],
        (Stop.CannotVerify) : [
            AttemptRecord.Result.CANNOT_VERIFY,
            new Stop.CannotVerify(new CheckRef(0, UntrustedText.branchDocument('ci')),
            UntrustedText.branchDocument('CI unreachable'), UntrustedText.branchDocument('503 x3'))
        ],
    ]

    private static TaskState stateWith(AttemptRecord.Result result, Stop stop) {
        def record = new AttemptRecord(0, result, STARTED_AT, [], ExecutorUsage.none(), JudgeUsage.none(), [], stop)
        new TaskState(new Position.AtStage('implement'), 1, [record], ExecutorUsage.none())
    }

    private static String write(TaskState state) {
        TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state))
    }

    private static TaskState read(String json) {
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json)))
    }

    private static String documentWithStop(String stopField) {
        """
        {
          "version": 1,
          "position": {"type": "atStage", "stage": "implement"},
          "attemptsUsed": 1,
          "attempts": [{
            "round": 0,
            "result": "decisionNeeded",
            "startedAt": "2026-10-07T09:00:00Z",
            "checks": [],
            "executorUsage": {"wallMillis": null, "tokensByModel": {}, "byTool": []},
            "judgeUsage": {"perVote": []}${stopField}
          }],
          "totals": {"wallMillis": null, "tokensByModel": {}, "byTool": []}
        }
        """
    }

    // FR10: no hand-listed subset — every permitted subclass has a sample and comes back equal
    def "the #variant.simpleName stop survives the state.json write and read"() {
        given:
        def sample = SAMPLES[variant]

        when:
        def state = stateWith(sample[0] as AttemptRecord.Result, sample[1] as Stop)

        then:
        sample != null
        read(write(state)) == state

        where:
        variant << (Stop.getPermittedSubclasses() as List)
    }

    // FR10, D5: the stop is written on every attempt, with its fields named as the contract states
    def "the stop is written as a typed object on the attempt, at version 1"() {
        when:
        def none = TaskStateJson.mapper().readTree(write(stateWith(AttemptRecord.Result.PASSED, Stop.none())))
        def decision = TaskStateJson.mapper().readTree(write(stateWith(*SAMPLES[Stop.DecisionNeeded])))
        def cannotVerify = TaskStateJson.mapper().readTree(write(stateWith(*SAMPLES[Stop.CannotVerify])))

        then:
        none.get('version').asInt() == 1
        none.get('attempts').get(0).get('stop').toString() == '{"type":"none"}'
        decision.get('attempts').get(0).get('stop').toString() ==
                '{"type":"decisionNeeded","question":"Ship it?","options":["yes","no"]}'
        cannotVerify.get('attempts').get(0).get('stop').toString() ==
                '{"type":"cannotVerify","check":"ci","reason":"CI unreachable","details":"503 x3"}'
    }

    // FR10: a document written before the field existed reads as a round that raised no stop
    def "an attempt with no stop field reads as none"() {
        expect:
        read(documentWithStop('')).attempts()[0].stop() == Stop.none()
    }

    // D3 of type-untrusted-text: what comes off the branch is the branch's text
    def "a stop's text is re-minted as branch text on the read"() {
        when:
        def stop = read(documentWithStop(
                        ',"stop": {"type": "decisionNeeded", "question": "Ship it?", "options": ["yes"]}'))
                .attempts()[0].stop() as Stop.DecisionNeeded

        then:
        stop.question() == UntrustedText.branchDocument('Ship it?')
        stop.options() == [
            UntrustedText.branchDocument('yes')
        ]
    }

    // FR10, D5: a token this build does not know fails closed — never read as a stop-less round
    def "an unknown stop.type makes the document unreadable"() {
        when:
        read(documentWithStop(',"stop": {"type": "somethingNew", "question": "?"}'))

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains('state.json')
        e.cause instanceof InvalidTypeIdException
    }
}
