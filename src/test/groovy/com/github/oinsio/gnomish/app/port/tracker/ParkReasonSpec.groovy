package com.github.oinsio.gnomish.app.port.tracker

import spock.lang.Specification

/**
 * ParkReason: why a task was parked into AwaitingHuman — ESCALATION, CHECKPOINT
 * or INFRA (design D3). Implements FR2 of add-tracker-port.
 */
class ParkReasonSpec extends Specification {

    // FR2: exactly the three reasons from the design D3 outcome mapping exist
    def "declares exactly ESCALATION, CHECKPOINT and INFRA"() {
        expect:
        ParkReason.values() as Set == [
            ParkReason.ESCALATION,
            ParkReason.CHECKPOINT,
            ParkReason.INFRA
        ] as Set
    }

    // FR2: each reason round-trips through valueOf, confirming the exact spelling
    def "each reason round-trips through name and valueOf"() {
        expect:
        ParkReason.valueOf(reason.name()) == reason

        where:
        reason << ParkReason.values()
    }
}
