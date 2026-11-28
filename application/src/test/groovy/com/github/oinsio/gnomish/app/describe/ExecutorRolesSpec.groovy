package com.github.oinsio.gnomish.app.describe

import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.executorcontract.Role
import spock.lang.Specification

/**
 * FR15 of define-executor-contract; FR13 of extract-agent-round-runner: the one mapping between the
 * domain's executor roles and the contract's maps every constant, through {@code values()}, onto its
 * namesake and back, and refuses a role outside the contract's set.
 */
class ExecutorRolesSpec extends Specification {

    def "NFR-R3: every domain role maps onto the contract role of its name, and every contract role back"() {
        expect:
        ExecutorRoles.contract(role).name() == role.name()
        ExecutorRoles.domain(ExecutorRoles.contract(role)) == role
        ExecutorRoles.contract(ExecutorRoles.domain(contract)) == contract

        where:
        role << ExecutorRole.values()
        contract << Role.values()
    }

    def "NFR-R3: a role outside the contract's set is refused"() {
        when:
        ExecutorRoles.domain(null)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "usage entry names a role outside the contract's set"
    }
}
