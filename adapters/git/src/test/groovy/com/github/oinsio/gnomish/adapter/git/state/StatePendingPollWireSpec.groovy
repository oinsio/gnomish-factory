package com.github.oinsio.gnomish.adapter.git.state

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * The {@code state.json} root array {@code pendingPolls} (FR2, FR20, NFR-R1 of
 * migrate-verify-to-executor-contract; design D20 as amended 2026-11-12, D21): the open round's list
 * survives the document text, each entry with the check's index, its attempt and its round token; no
 * pending poll omits the array, and an absent array reads as an empty list; a refused key, a token
 * that is not a commit id, a missing field or a malformed instant makes the file malformed.
 */
class StatePendingPollWireSpec extends Specification {

    private static final Instant STARTED = Instant.parse('2026-11-12T09:00:00Z')
    private static final Instant SINCE = Instant.parse('2026-11-12T09:01:30Z')
    private static final String COMMIT = '0123456789abcdef0123456789abcdef01234567'
    private static final RoundToken TOKEN = RoundToken.of(COMMIT)

    private static PendingPollRecord poll(int index, int attempt, RoundToken token, String key) {
        new PendingPollRecord(new CheckRef(index, UntrustedText.branchDocument('executor:scm-status:ci')), attempt,
                token, new PendingKey(key), SINCE)
    }

    private static TaskState state() {
        def last = AttemptRecords.of(0, AttemptRecord.Result.QUALITY_FAILURE, STARTED, [], ExecutorUsage.none(), [],
        Stop.none())
        new TaskState(new Position.AtStage('verify'), 1, [last], UsageSnapshot.EMPTY, ExecutorVersions.none())
    }

    private static String write(TaskState state) {
        TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state))
    }

    private static TaskState read(String json) {
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json)))
    }

    /** The written document of a state with one pending poll, its first entry edited by {@code edit}. */
    private static String edited(Closure edit) {
        def tree = TaskStateJson.mapper().readTree(write(state().withPendingPolls([poll(2, 1, TOKEN, 'run-1')])))
        edit((tree.get('pendingPolls') as ArrayNode).get(0) as ObjectNode)
        TaskStateJson.mapper().writeValueAsString(tree)
    }

    // FR2, FR20, D21: toDto -> JSON text -> fromDto is the identity, a list of two entries, an
    //     in-place entry and a key of exactly the leaf's cap included
    def "a list of #polls.size() pending polls survives the state.json write and read"() {
        given:
        def state = state().withPendingPolls(polls)

        expect:
        read(write(state)) == state
        read(write(state)).pendingPolls()*.check()*.index() == polls*.check()*.index()

        where:
        polls << [
            [poll(0, 0, TOKEN, 'k')],
            [
                poll(0, 1, TOKEN, 'run-1'),
                poll(2, 1, TOKEN, 'k' * PendingKey.MAX_LENGTH)
            ],
            [poll(1, 3, null, 'in-place')]
        ]
    }

    // D20 as amended: the shape as the design states it — a root array whose elements carry the
    //     check's index in full, their own attempt and token; the attempts carry no pending object
    def "the pending polls are written as a root array, each entry naming its check, attempt and token"() {
        when:
        def tree = TaskStateJson.mapper().readTree(write(state().withPendingPolls([
            poll(2, 1, TOKEN, 'run-1'),
            poll(3, 1, null, 'run-2')
        ])))

        then:
        tree.get('pendingPolls').toString() == '[' +
                '{"check":{"index":2,"ref":"executor:scm-status:ci"},"attempt":1,"token":"' + COMMIT +
                '","key":"run-1","since":"2026-11-12T09:01:30Z"},' +
                '{"check":{"index":3,"ref":"executor:scm-status:ci"},"attempt":1,' +
                '"key":"run-2","since":"2026-11-12T09:01:30Z"}]'
        !tree.get('attempts').get(0).has('pendingPoll')
    }

    // D20 as amended: a state with no pending poll omits the array; a document without it reads empty
    def "a state with no pending poll omits the array, and a document without the array reads as an empty list"() {
        given:
        def tree = TaskStateJson.mapper().readTree(write(state().withPendingPolls([poll(0, 1, TOKEN, 'run-1')])))
        (tree as ObjectNode).remove('pendingPolls')

        when:
        def json = write(state())

        then:
        !TaskStateJson.mapper().readTree(json).has('pendingPolls')
        read(json) == state()
        read(TaskStateJson.mapper().writeValueAsString(tree)).pendingPolls() == []
    }

    // D20: the cap is the leaf's, by construction; D20 as amended: a missing field, a negative
    //     number, a token that is not a commit id or a malformed instant is a malformed state file
    //     naming the field, never the value
    def "a pending poll entry whose #field #rule makes the state file malformed"() {
        when:
        read(edited(edit))

        then:
        def e = thrown(MalformedStateFileException)
        e.message.startsWith('state.json: pendingPolls[].' + field)

        where:
        field | rule | edit
        'key' | 'is over the cap' | { ObjectNode p ->
            p.put('key', 'k' * (PendingKey.MAX_LENGTH + 1))
        }
        'key' | 'holds a control character' | { ObjectNode p ->
            p.put('key', 'run\u00001')
        }
        'key' | 'is blank' | { ObjectNode p ->
            p.put('key', ' ')
        }
        'key' | 'is missing' | { ObjectNode p ->
            p.remove('key')
        }
        'attempt' | 'is missing' | { ObjectNode p ->
            p.remove('attempt')
        }
        'attempt' | 'is negative' | { ObjectNode p ->
            p.put('attempt', -1)
        }
        'token' | 'is not a commit id' | { ObjectNode p ->
            p.put('token', 'HEAD~1')
        }
        'token' | 'is blank' | { ObjectNode p ->
            p.put('token', '')
        }
        'check' | 'is missing' | { ObjectNode p ->
            p.remove('check')
        }
        'check.index' | 'is missing' | { ObjectNode p ->
            (p.get('check') as ObjectNode).remove('index')
        }
        'check.index' | 'is negative' | { ObjectNode p ->
            (p.get('check') as ObjectNode).put('index', -1)
        }
        'check.ref' | 'is missing' | { ObjectNode p ->
            (p.get('check') as ObjectNode).remove('ref')
        }
        'check.ref' | 'is blank' | { ObjectNode p ->
            (p.get('check') as ObjectNode).put('ref', ' ')
        }
        'since' | 'is missing' | { ObjectNode p ->
            p.remove('since')
        }
        'since' | 'is not an instant' | { ObjectNode p ->
            p.put('since', 'yesterday')
        }
    }

    // the refusal names the field and the rule, never the 4097-character value it refused
    def "the refusal of an oversized key does not carry the key"() {
        when:
        read(edited { ObjectNode p ->
            p.put('key', 'k' * (PendingKey.MAX_LENGTH + 1))
        })

        then:
        def e = thrown(MalformedStateFileException)
        e.message == 'state.json: pendingPolls[].key is unusable: it is longer than 4096 characters'
    }

    // D20 as amended: the token goes back through the one parse, and its refusal does not quote the value
    def "the refusal of a malformed token does not carry the token"() {
        when:
        read(edited { ObjectNode p ->
            p.put('token', 'refs/heads/secret-name')
        })

        then:
        def e = thrown(MalformedStateFileException)
        e.message == 'state.json: pendingPolls[].token is not a round token: ' +
                'a round token must be a non-blank lowercase hexadecimal commit id'
    }
}
