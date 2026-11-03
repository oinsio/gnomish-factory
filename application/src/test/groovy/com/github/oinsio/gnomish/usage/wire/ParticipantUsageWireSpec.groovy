package com.github.oinsio.gnomish.usage.wire

import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.ParticipantUsage
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import com.github.oinsio.gnomish.domain.engine.UsageProvenance
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.usagewire.ParticipantUsageDto
import com.github.oinsio.gnomish.executorcontract.usagewire.RecordedProvenance
import java.time.Duration
import spock.lang.Specification

/**
 * FR15, FR16, NFR-R3 of define-executor-contract: the one mapping between the domain's participant
 * entries and the usage wire vocabulary round-trips every entry — every role and provenance
 * constant, through {@code values()} — keeps an unpriced model unpriced, writes the model map in
 * model-id order, and refuses what the record cannot name (design D10, D14).
 */
class ParticipantUsageWireSpec extends Specification {

    private static final TokenUsage TOKENS = new TokenUsage(1, 2, 3, 4)

    private static ParticipantUsage entry(ExecutorRole role, UsageProvenance provenance) {
        new ParticipantUsage(role, 'acme', role == ExecutorRole.VERDICT ? 3 : null, provenance, Duration.ofMillis(7),
                [
                    new ToolUsage('Edit', 2, Duration.ofMillis(5))
                ],
                ['m': new ModelUsage(TOKENS, new BigDecimal('0.5')), 'n': new ModelUsage(TOKENS, null)])
    }

    def "NFR-R3: a #role entry reads back as itself through the wire"() {
        given:
        def original = entry(role, UsageProvenance.REPORTED)

        expect:
        ParticipantUsageWire.fromDtos(ParticipantUsageWire.toDtos([original])) == [original]

        where:
        role << ExecutorRole.values()
    }

    def "NFR-R3: every domain role maps onto the contract role of its name, and every contract role back"() {
        expect:
        ParticipantUsageWire.toRole(role).name() == role.name()
        ParticipantUsageWire.fromRole(ParticipantUsageWire.toRole(role)) == role
        ParticipantUsageWire.toRole(ParticipantUsageWire.fromRole(contract)) == contract

        where:
        role << ExecutorRole.values()
        contract << Role.values()
    }

    def "NFR-R3: provenance #provenance maps to #wire and back"() {
        expect:
        ParticipantUsageWire.toProvenance(provenance) == wire
        ParticipantUsageWire.fromProvenance(wire) == provenance
        ParticipantUsageWire.fromDtos(ParticipantUsageWire.toDtos([
            entry(ExecutorRole.TRANSFORM, provenance)
        ]))[0]
        .provenance() == provenance

        where:
        provenance || wire
        UsageProvenance.REPORTED || RecordedProvenance.REPORTED
        UsageProvenance.ABSENT || RecordedProvenance.ABSENT
    }

    def "NFR-R3: the provenance mapping covers both sets whole"() {
        expect:
        UsageProvenance.values().collect {
            ParticipantUsageWire.toProvenance(it)
        } as Set == RecordedProvenance.values() as Set
    }

    def "FR15: the wire entry carries every component, the model map in model-id order and the cost only where priced"() {
        given:
        def unordered = new ParticipantUsage(ExecutorRole.TRANSFORM, 'acme', null, UsageProvenance.REPORTED, null, [],
        ['z': new ModelUsage(TOKENS, null), 'a': new ModelUsage(TOKENS, new BigDecimal('1.25'))])

        when:
        def dto = ParticipantUsageWire.toDtos([unordered])[0]

        then:
        dto.role() == Role.TRANSFORM
        dto.executor() == 'acme'
        dto.vote() == null
        dto.wallMillis() == null
        dto.byTool() == []
        dto.byModel().keySet() as List == ['a', 'z']
        dto.byModel()['a'].costUsd() == new BigDecimal('1.25')
        dto.byModel()['z'].costUsd() == null
        dto.byModel()['z'].input() == 1L
    }

    def "NFR-R3: an entry naming a role outside the contract's set is refused, never read"() {
        when:
        ParticipantUsageWire.fromDtos([
            new ParticipantUsageDto(null, 'acme', null, RecordedProvenance.ABSENT, null, [], [:])
        ])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "usage entry names a role outside the contract's set"
    }

    def "FR15: a recorded tool aggregate without its duration is refused, never read as zero"() {
        when:
        ParticipantUsageWire.fromDtos([
            new ParticipantUsageDto(Role.TRANSFORM, 'acme', null, RecordedProvenance.REPORTED,
            null, [
                new com.github.oinsio.gnomish.executorcontract.ToolUsage('Edit', 1, null)
            ], [:])
        ])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "recorded tool aggregate 'Edit' has no totalMillis"
    }
}
