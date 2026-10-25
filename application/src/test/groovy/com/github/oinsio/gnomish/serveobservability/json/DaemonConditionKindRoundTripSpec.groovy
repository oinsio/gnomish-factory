package com.github.oinsio.gnomish.serveobservability.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.oinsio.gnomish.app.take.DaemonConditionKind
import com.github.oinsio.gnomish.serveobservability.ConditionHealth
import com.github.oinsio.gnomish.serveobservability.ConditionTokens
import com.github.oinsio.gnomish.serveobservability.Snapshot
import java.time.Instant
import spock.lang.Specification

/**
 * NFR-O2, UX4 of add-sandbox-base-image (design D19): the condition vocabulary on the wire. Every
 * {@link DaemonConditionKind} has one token ({@link ConditionTokens}) that reads back as itself,
 * every kind in every state survives the snapshot's {@code conditions} section written and read,
 * a token a newer factory wrote is kept as read rather than dropped, and a snapshot written before
 * the section existed is read with the remote as its one condition.
 */
class DaemonConditionKindRoundTripSpec extends Specification {

    private static final Instant SINCE = Instant.parse('2026-08-02T08:00:00Z')

    def mapper = new SnapshotJsonMapper()
    def reader = new SnapshotJsonReader()

    private static Snapshot withConditions(List<ConditionHealth> conditions) {
        def base = SnapshotJsonMapperSpec.referenceSnapshot()
        new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(), base.lifecycle(),
                base.feed(), base.slots(), base.vitals(), base.tracker(), base.remote(), conditions)
    }

    private static ConditionHealth entry(String token, boolean open) {
        open
                ? new ConditionHealth(token, null, true, SINCE, 'refused; the fix', SINCE.plusSeconds(60), 3, null)
                : new ConditionHealth(token, null, false, null, null, null, 0, SINCE)
    }

    def "every kind has one token that reads back as itself: #kind"() {
        expect:
        ConditionTokens.kind(ConditionTokens.token(kind)) == Optional.of(kind)

        where:
        kind << DaemonConditionKind.values()
    }

    def "every token is distinct"() {
        expect:
        DaemonConditionKind.values().collect {
            ConditionTokens.token(it)
        }.toSet().size() == DaemonConditionKind.values().length
    }

    // Scenario: observability/serve-observability / Every condition kind round-trips the snapshot
    def "every kind, open or closed, survives the snapshot's conditions section: #kind open=#open"() {
        given:
        def written = withConditions([
            entry(ConditionTokens.token(kind), open)
        ])

        when:
        def read = reader.read(mapper.serialize(written))

        then:
        read.conditions() == written.conditions()
        ConditionTokens.kind(read.conditions()[0].condition()) == Optional.of(kind)

        where:
        [kind, open] << [
            DaemonConditionKind.values().toList(),
            [true, false]
        ].combinations()
    }

    def "a token this version does not know is kept as read"() {
        given:
        def written = withConditions([entry('diskSpace', true)])

        when:
        def read = reader.read(mapper.serialize(written))

        then:
        read.conditions()*.condition() == ['diskSpace']
        ConditionTokens.kind('diskSpace').isEmpty()
    }

    // Scenario: observability/serve-observability / A snapshot with only the remote section is read as one condition
    def "a snapshot written before the conditions section reads its remote as the one condition"() {
        given:
        def json = new ObjectMapper().readTree(mapper.serialize(SnapshotJsonMapperSpec.referenceSnapshot()))
        ((ObjectNode) json).remove('conditions')

        when:
        def read = reader.read(json.toString())

        then:
        read.conditions().size() == 1
        read.conditions()[0].condition() == ConditionTokens.REMOTE
        read.conditions()[0].target() == 'origin'
        !read.conditions()[0].open()
    }

    // Scenario: observability/serve-observability / A conditions entry names the precondition, not a task
    def "a conditions entry names its condition and carries no task field"() {
        given:
        def json = new ObjectMapper().readTree(mapper.serialize(withConditions([entry('cloneRefFormat', true)])))

        expect:
        def node = json.get('conditions').get(0)
        node.fieldNames().toList() == [
            'condition',
            'target',
            'state',
            'openSince',
            'lastError',
            'nextProbeAt',
            'consecutiveFailures',
            'lastSuccessAt'
        ]
        node.get('condition').asText() == 'cloneRefFormat'
        node.get('state').asText() == 'open'
        node.get('target').isNull()
    }
}
