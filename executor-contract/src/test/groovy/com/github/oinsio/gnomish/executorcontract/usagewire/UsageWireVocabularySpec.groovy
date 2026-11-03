package com.github.oinsio.gnomish.executorcontract.usagewire

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.executorcontract.ModelUsage
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.ToolUsage
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR15, FR16, NFR-R3 of define-executor-contract: the one usage wire vocabulary round-trips every
 * token it spells — every {@link RecordedProvenance} constant through {@code fromWire(wire(e))} and
 * through Jackson, every contract role as a participant entry's {@code role} — pins each unknown-token
 * arm, and writes a participant entry in the record documents' shape (testing.md, "Every wire
 * vocabulary has a round-trip spec"). The record documents read with a plain mapper that ignores
 * unknown fields, as {@code state.json}'s, the status report's and the usage report's do.
 */
class UsageWireVocabularySpec extends Specification {

    private static final ObjectMapper RECORD_MAPPER =
    new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private static final List<Class<? extends Enum>> WIRE_ENUMS = [RecordedProvenance]

    def "NFR-R3: the vocabulary list names every enum of the package"() {
        given:
        def packageDir = Path.of(RecordedProvenance.getResource('RecordedProvenance.class').toURI()).parent
        def found = Files.list(packageDir).withCloseable { files ->
            files.map { it.fileName.toString() }
            .filter { it.endsWith('.class') && !it.contains('$') }
            .map {
                Class.forName("${RecordedProvenance.package.name}.${it - '.class'}")
            }
            .filter { it.isEnum() }
            .toList()
        }

        expect:
        found as Set == WIRE_ENUMS as Set
    }

    def "NFR-R3: provenance #constant reads back as itself from its token '#constant.wire()'"() {
        expect:
        RecordedProvenance.fromWire(constant.wire()) == Optional.of(constant)
        RECORD_MAPPER.writeValueAsString(constant) == "\"${constant.wire()}\""
        RECORD_MAPPER.readValue(RECORD_MAPPER.writeValueAsString(constant), RecordedProvenance) == constant

        where:
        constant << RecordedProvenance.values()
    }

    def "NFR-R3: the provenance tokens are the record documents' lowerCamel words, each distinct"() {
        expect:
        RecordedProvenance.values()*.wire() == ['reported', 'absent']
        RecordedProvenance.fromWire('REPORTED') == Optional.empty()
        RecordedProvenance.fromWire('') == Optional.empty()
    }

    def "NFR-R3: an unknown provenance token reads as absent, never as reported"() {
        expect:
        RECORD_MAPPER.readValue('"made-up"', RecordedProvenance) == RecordedProvenance.ABSENT
    }

    def "FR16: a #role participant entry round-trips through the record documents' binding"() {
        given:
        def entry = new ParticipantUsageDto(role, 'acme', role == Role.VERDICT ? 1 : null, RecordedProvenance.REPORTED,
                10L, [new ToolUsage('Edit', 2, 5L)], ['m': new ModelUsage(1, 2, 3, 4, new BigDecimal('0.5'))])

        expect:
        RECORD_MAPPER.readValue(RECORD_MAPPER.writeValueAsString(entry), ParticipantUsageDto) == entry
        RECORD_MAPPER.readTree(RECORD_MAPPER.writeValueAsString(entry)).get('role').asText() == role.wire()

        where:
        role << Role.values()
    }

    def "FR15: an entry is written in the record shape: vote and wall time only when present, tools and models always"() {
        given:
        def entry = new ParticipantUsageDto(Role.TRANSFORM, 'agent-cli', null, RecordedProvenance.ABSENT, null, [], [:])

        expect:
        RECORD_MAPPER.writeValueAsString(entry) ==
                '{"role":"transform","executor":"agent-cli","provenance":"absent","byTool":[],"byModel":{}}'
    }

    def "FR15: an unpriced model carries no costUsd key, a priced one carries its cost"() {
        given:
        def entry = new ParticipantUsageDto(Role.VERDICT, 'agent-cli', 0, RecordedProvenance.REPORTED, null, [],
        ['a': new ModelUsage(1, 2, 3, 4, null), 'b': new ModelUsage(1, 2, 3, 4, new BigDecimal('0.1607'))])

        expect:
        RECORD_MAPPER.writeValueAsString(entry) ==
                '{"role":"verdict","executor":"agent-cli","vote":0,"provenance":"reported","byTool":[],"byModel":' +
                '{"a":{"input":1,"output":2,"cacheCreation":3,"cacheRead":4},' +
                '"b":{"input":1,"output":2,"cacheCreation":3,"cacheRead":4,"costUsd":0.1607}}}'
    }

    def "NFR-R3: an unknown role token reads as no role, and absent lists read as empty"() {
        when:
        def entry = RECORD_MAPPER.readValue('{"role":"oracle","executor":"x","provenance":"absent"}', ParticipantUsageDto)

        then:
        entry.role() == null
        entry.byTool() == []
        entry.byModel() == [:]
    }

    def "FR16, FR17: the snapshot round-trips in the record documents' shape, its counts never priced"() {
        given:
        def tokens = new ModelUsage(1, 2, 3, 4, null)
        def snapshot = new UsageSnapshotDto(
                ['transform': ['m': tokens]],
                new UsageCostDto(new BigDecimal('0.1922'), ['verdict': ['j': tokens]], false),
                2,
                '9f1c2b7a4e6d8c0b1a2f3e4d5c6b7a8f9e0d1c2b')

        expect:
        RECORD_MAPPER.readValue(RECORD_MAPPER.writeValueAsString(snapshot), UsageSnapshotDto) == snapshot
        RECORD_MAPPER.writeValueAsString(snapshot) ==
                '{"byRole":{"transform":{"m":{"input":1,"output":2,"cacheCreation":3,"cacheRead":4}}},' +
                '"cost":{"priced":0.1922,"unpricedTokens":{"verdict":{"j":{"input":1,"output":2,"cacheCreation":3,"cacheRead":4}}},' +
                '"complete":false},"rounds":2,"through":"9f1c2b7a4e6d8c0b1a2f3e4d5c6b7a8f9e0d1c2b"}'
    }

    def "FR17: a snapshot with no token omits through, and one without its cost is refused"() {
        given:
        def empty = new UsageSnapshotDto([:], new UsageCostDto(BigDecimal.ZERO, [:], true), 0, null)

        expect:
        RECORD_MAPPER.writeValueAsString(empty) ==
                '{"byRole":{},"cost":{"priced":0,"unpricedTokens":{},"complete":true},"rounds":0}'
        RECORD_MAPPER.readValue(RECORD_MAPPER.writeValueAsString(empty), UsageSnapshotDto) == empty

        when:
        RECORD_MAPPER.readValue('{"byRole":{},"rounds":0}', UsageSnapshotDto)

        then:
        thrown(com.fasterxml.jackson.databind.JsonMappingException)
    }
}
