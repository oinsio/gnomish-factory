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
}
