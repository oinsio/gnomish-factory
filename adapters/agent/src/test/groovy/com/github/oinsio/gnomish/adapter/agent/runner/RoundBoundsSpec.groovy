package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Role
import java.math.BigDecimal
import java.time.Duration
import spock.lang.Specification

/**
 * The one resolution of a round's bounds (FR7, FR16, NFR-R1 of extract-agent-round-runner; design D6
 * as amended 2026-11-21 and 2026-11-22, the {@code RoundBounds} row and the row of the bounds on a
 * quiet stdout): the overall bound from the settings' {@code roundTimeout} when they declare one and
 * the factory's otherwise; the factory's startup-read and silence bounds when the dialect's describe
 * declares {@code streams_progress}, none otherwise; the request's time hints are that one value, its
 * turn, token and money hints left as they came.
 */
class RoundBoundsSpec extends Specification {

    static final ProgramBounds FACTORY =
    new ProgramBounds(Duration.ofSeconds(7), Duration.ofMinutes(3), Duration.ofMinutes(30))

    static final Describe STREAMING = new FakeDialect().describe()

    static final Describe QUIET = new FakeDialect(capabilities: [
        Capability.POLL,
        Capability.CANCEL
    ]).describe()

    // Scenario: executor/agent-round-runner / A declared roundTimeout is the round's overall bound
    def "FR7: a declared roundTimeout of #declared is the overall bound"() {
        expect:
        RoundBounds.overall(FACTORY, [roundTimeout: declared, maxTurns: 4]) == overall
        RoundBounds.of(FACTORY, STREAMING, request([roundTimeout: declared], Bounds.none())).overall() == overall

        where:
        declared || overall
        28800 || Duration.ofSeconds(28800)
        '28800s' || Duration.ofHours(8)
        'PT10M' || Duration.ofMinutes(10)
    }

    // Scenario: executor/agent-round-runner / Without a roundTimeout the factory's overall bound applies
    def "FR7: settings that declare no roundTimeout are bounded by the factory's overall bound"() {
        expect:
        RoundBounds.overall(FACTORY, settings) == FACTORY.overall()
        RoundBounds.of(FACTORY, STREAMING, request(settings, Bounds.none())).overall() == FACTORY.overall()

        where:
        settings << [[:], [maxTurns: 4]]
    }

    def "FR7: the request's settings tree is read for its roundTimeout"() {
        expect:
        RoundBounds.overall(FACTORY, request([roundTimeout: '28800s'], Bounds.none())) == Duration.ofHours(8)
        RoundBounds.overall(FACTORY, request([:], Bounds.none())) == FACTORY.overall()
    }

    // FR7: the load judge refuses a non-positive roundTimeout; one that reaches a round declares
    //     nothing, like every value with no accepted shape, so the round is never bounded by it
    def "FR7: a roundTimeout that is not a positive duration (#value) declares nothing, so the factory's bound applies"() {
        expect:
        RoundBounds.overall(FACTORY, [roundTimeout: value]) == FACTORY.overall()

        where:
        value << [0, -5, 'PT0S']
    }

    def "NFR-R1: the hinted request of a streams_progress dialect carries the three resolved bounds as its time hints, the rest of it unchanged"() {
        given:
        def original = request([roundTimeout: 28800],
        new Bounds(9L, 1000L, new BigDecimal('2.50'), 1L, 2L, 3L))

        when:
        def hinted = RoundBounds.of(FACTORY, STREAMING, original).hinted(original)

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

    def "FR16: the hinted request of a dialect that declares no streams_progress carries no startup-read and no silence hint"() {
        given:
        def original = request([:], new Bounds(9L, null, null, 1L, 2L, 3L))

        expect:
        RoundBounds.of(FACTORY, QUIET, original).hinted(original).bounds() ==
                new Bounds(9L, null, null, null, null, FACTORY.overall().toMillis())
    }

    def "NFR-R1: the wait is built over the resolved bounds"() {
        expect:
        RoundBounds.of(FACTORY, describe, request([roundTimeout: 60], Bounds.none()))
        .waitOn(VirtualTimeEquipment.create()) != null

        where:
        describe << [STREAMING, QUIET]
    }

    private static ExecutorRequest request(Map settings, Bounds bounds) {
        def base = RunnerFixture.request(Role.TRANSFORM)
        new ExecutorRequest(base.role(), base.workspace(), base.policy(), base.roleInputs(),
                ExecutorContractJson.settingsTree(settings), bounds, [], null)
    }
}
