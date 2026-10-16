package com.github.oinsio.gnomish.adapter.git

import spock.lang.Specification

/**
 * FR13 of tighten-spec-contract (design D13, D18): the clone's factory register has one name per
 * task, sharing the branch's core string; its fetch refspecs force it, in place or onto its
 * remote-tracking name; and the harvest quarantine is named from the same core string.
 */
class FactoryRegisterRefsSpec extends Specification {

    def "FR13: the register shares the branch's core string, and its fetch refspecs force it"() {
        expect:
        FactoryRegisterRefs.of('PROJ 7: fix/it').name() == 'refs/gnomish-factory/PROJ-7-fix-it'
        FactoryRegisterRefs.quarantine('PROJ 7: fix/it').name() == 'refs/gnomish-quarantine/PROJ-7-fix-it'
        FactoryRegisterRefs.fetchedInPlace('T-1').value() == '+refs/gnomish-factory/T-1:refs/gnomish-factory/T-1'
        FactoryRegisterRefs.fetchedTracking('T-1').value() == '+refs/gnomish-factory/T-1:refs/remotes/origin/gnomish-factory/T-1'
    }
}
