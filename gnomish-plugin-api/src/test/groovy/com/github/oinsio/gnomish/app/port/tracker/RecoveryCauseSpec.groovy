package com.github.oinsio.gnomish.app.port.tracker

import spock.lang.Specification

/**
 * RecoveryCause: the two categories of the unified recovery accounting — a crashed run and a failed
 * branch repair — sharing one counter (design D9). Implements FR14 of harden-task-branch-contract.
 */
class RecoveryCauseSpec extends Specification {

    // FR14: exactly two categories, so a report that names both names the whole history
    def "declares exactly INSTANCE_CRASH and RECOVERY_FAILURE"() {
        expect:
        RecoveryCause.values() as Set == [
            RecoveryCause.INSTANCE_CRASH,
            RecoveryCause.RECOVERY_FAILURE
        ] as Set
    }

    // FR14: the wire value is what adapters persist, so it is lowercase and stable
    def "#cause renders the lowercase wire value #wire"() {
        expect:
        cause.wireValue() == wire

        where:
        cause | wire
        RecoveryCause.INSTANCE_CRASH | 'instance_crash'
        RecoveryCause.RECOVERY_FAILURE | 'recovery_failure'
    }

    // FR14, testing.md "Every wire vocabulary has a round-trip spec": iterated over values(), so a
    // constant added on the writer side without a reader mapping fails here, not in production.
    def "#cause survives the wire round-trip"() {
        expect:
        RecoveryCause.fromWire(cause.wireValue()) == cause

        where:
        cause << RecoveryCause.values()
    }

    // FR14: the forward-compat default — an unknown future category degrades the report's split
    // into the crash share, never its total, same as a pre-categorization marker with no token.
    def "an unknown wire token reads as the crash category"() {
        expect:
        RecoveryCause.fromWire('some_future_category') == RecoveryCause.INSTANCE_CRASH
    }
}
