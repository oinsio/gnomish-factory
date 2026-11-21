package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Role
import java.math.BigDecimal
import java.time.Duration
import spock.lang.Specification

/**
 * The one resolution of a round's three bounds (FR7, NFR-R1 of extract-agent-round-runner; design D6
 * as amended 2026-11-21, the {@code RoundBounds} row): the factory's startup-read and silence bounds
 * always, the overall bound from the settings' {@code roundTimeout} when they declare one and the
 * factory's otherwise; the request's time hints are that one value, its turn, token and money hints
 * left as they came.
 */
class RoundBoundsSpec extends Specification {

    static final ProgramBounds FACTORY =
    new ProgramBounds(Duration.ofSeconds(7), Duration.ofMinutes(3), Duration.ofMinutes(30))

    // Scenario: executor/agent-round-runner / A declared roundTimeout is the round's overall bound
    def "FR7: a declared roundTimeout of #declared is the overall bound, the factory's startup-read and silence kept"() {
        when:
        def bounds = RoundBounds.of(FACTORY, [roundTimeout: declared, maxTurns: 4])

        then:
        bounds.bounds() == new ProgramBounds(FACTORY.startup(), FACTORY.silence(), overall)
        bounds.overall() == overall

        where:
        declared || overall
        28800 || Duration.ofSeconds(28800)
        '28800s' || Duration.ofHours(8)
        'PT10M' || Duration.ofMinutes(10)
    }

    // Scenario: executor/agent-round-runner / Without a roundTimeout the factory's overall bound applies
    def "FR7: settings that declare no roundTimeout are bounded by the factory's own three bounds"() {
        expect:
        RoundBounds.of(FACTORY, settings).bounds() == FACTORY

        where:
        settings << [[:], [maxTurns: 4]]
    }

    def "FR7: the request's settings tree is read for its roundTimeout"() {
        given:
        def declaring = request([roundTimeout: '28800s'], Bounds.none())

        expect:
        RoundBounds.of(FACTORY, declaring).overall() == Duration.ofHours(8)
        RoundBounds.of(FACTORY, request([:], Bounds.none())).bounds() == FACTORY
    }

    def "FR7: a roundTimeout that is not a positive duration is refused, never bounded by"() {
        when:
        RoundBounds.of(FACTORY, [roundTimeout: 0])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains('overall')
    }

    def "NFR-R1: the hinted request carries the three resolved bounds as its time hints, the rest of it unchanged"() {
        given:
        def original = request([roundTimeout: 28800],
        new Bounds(9L, 1000L, new BigDecimal('2.50'), 1L, 2L, 3L))

        when:
        def hinted = RoundBounds.of(FACTORY, original).hinted(original)

        then:
        hinted.bounds() == new Bounds(9L, 1000L, new BigDecimal('2.50'),
                FACTORY.startup().toMillis(), FACTORY.silence().toMillis(), Duration.ofSeconds(28800).toMillis())
        hinted.role() == original.role()
        hinted.workspace() == original.workspace()
        hinted.policy() == original.policy()
        hinted.roleInputs() == original.roleInputs()
        hinted.settings().is(original.settings())
        hinted.feedback() == original.feedback()
        hinted.continuation() == original.continuation()
    }

    def "NFR-R1: the wait is built over the resolved bounds"() {
        expect:
        RoundBounds.of(FACTORY, [roundTimeout: 60]).waitOn(VirtualTimeEquipment.create()) != null
    }

    private static ExecutorRequest request(Map settings, Bounds bounds) {
        def base = RunnerFixture.request(Role.TRANSFORM)
        new ExecutorRequest(base.role(), base.workspace(), base.policy(), base.roleInputs(),
                ExecutorContractJson.settingsTree(settings), bounds, [], null)
    }
}
