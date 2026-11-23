package com.github.oinsio.gnomish.adapter.git.state

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * The subject of a {@code state.json} pending poll element (FR10, NFR-R3 of
 * extract-agent-round-runner; design D11): every subject the sealed {@link PendingSubject} declares
 * survives the write and read under its own token; an element written before the token existed
 * reads as a check's; an unknown token, a check's element without its check and a round's element
 * carrying one make the file malformed, never a check and never skipped — so the entry is never
 * polled.
 */
class StatePendingSubjectWireSpec extends Specification {

    private static final RoundToken TOKEN = RoundToken.of('0123456789abcdef0123456789abcdef01234567')
    private static final Instant SINCE = Instant.parse('2026-11-23T09:01:30Z')
    private static final PendingSubject CHECK =
    new PendingSubject.Check(new CheckRef(2, UntrustedText.branchDocument('executor:scm-status:ci')))

    /** One instance of every subject, keyed by its class, with the token it is written under. */
    private static final Map<Class<?>, List<Object>> EVERY_SUBJECT = [
        (PendingSubject.Check): [CHECK, 'check'],
        (PendingSubject.Round): [PendingSubject.ROUND, 'round'],
    ]

    private static TaskState state(PendingSubject subject) {
        TaskState.atStageStart('verify').withPendingPolls([
            new PendingPollRecord(subject, 1, TOKEN, new PendingKey('run-1'), SINCE)
        ])
    }

    private static String write(TaskState state) {
        TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state))
    }

    private static TaskState read(String json) {
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json)))
    }

    private static ObjectNode element(String json) {
        (TaskStateJson.mapper().readTree(json).get('pendingPolls') as ArrayNode).get(0) as ObjectNode
    }

    /** The document of a state with one pending poll of {@code subject}, its element edited by {@code edit}. */
    private static String edited(PendingSubject subject, Closure edit) {
        def tree = TaskStateJson.mapper().readTree(write(state(subject)))
        edit((tree.get('pendingPolls') as ArrayNode).get(0) as ObjectNode)
        TaskStateJson.mapper().writeValueAsString(tree)
    }

    // D11: the table above names every subject the sealed type permits, so a new subject fails here
    def "every permitted subject has a row"() {
        expect:
        EVERY_SUBJECT.keySet() == (PendingSubject.permittedSubclasses as List<Class<?>>) as Set
    }

    // D11, testing.md wire round-trip: fromWire(wire(s)) == s for every subject, under its own token
    def "the #token subject survives the state.json write and read under its own token"() {
        given:
        def state = state(subject)
        def json = write(state)

        expect:
        element(json).get('subject').asText() == token
        read(json) == state
        read(json).pendingPolls()*.subject() == [subject]

        where:
        [subject, token] << EVERY_SUBJECT.values()
    }

    // D11: the round names nothing beyond the record's own attempt and token, so it writes no check object
    def "a round's element is written with no check object"() {
        expect:
        !element(write(state(PendingSubject.ROUND))).has('check')
        element(write(state(CHECK))).get('check').get('index').asInt() == 2
    }

    // D11: every element written by migrate-verify-to-executor-contract carries no subject and reads as a check's
    def "an element with no subject reads as a check's"() {
        when:
        def read = read(edited(CHECK) { ObjectNode p -> p.remove('subject') })

        then:
        read == state(CHECK)
    }

    // testing.md: the unknown-token arm is pinned — a malformed file naming the field, never the
    //     value, so an entry this build cannot name is never polled
    def "an element whose subject #why makes the state file malformed"() {
        when:
        read(edited(subject, edit))

        then:
        def e = thrown(MalformedStateFileException)
        e.message == 'state.json: pendingPolls[].' + detail

        where:
        why | subject | edit | detail
        'is an unknown token' | CHECK | { ObjectNode p ->
            p.put('subject', 'stage-secret')
        } | 'subject is not a known pending subject'
        'is the round but a check is carried' | CHECK | { ObjectNode p ->
            p.put('subject', 'round')
        } | 'check must be absent for the round subject'
        'is a check with no check object' | PendingSubject.ROUND | { ObjectNode p ->
            p.put('subject', 'check')
        } | 'check is missing'
    }
}
