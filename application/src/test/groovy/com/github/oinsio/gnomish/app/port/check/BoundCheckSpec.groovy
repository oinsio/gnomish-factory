package com.github.oinsio.gnomish.app.port.check

import com.github.oinsio.gnomish.executorcontract.Executor
import spock.lang.Specification

/**
 * FR3, FR11 of migrate-verify-to-executor-contract (design D11 as amended 2026-11-14): a bound check
 * names its executor and the release of the environment it was bound in; an executor bound in no
 * environment of its own releases nothing, and neither member may be missing.
 */
class BoundCheckSpec extends Specification {

    def "FR11: an unboxed executor is bound with a release that does nothing"() {
        given:
        def executor = Stub(Executor)

        when:
        def bound = BoundCheck.unboxed(executor)
        bound.release().run()

        then:
        bound.executor().is(executor)
        noExceptionThrown()
    }

    def "FR11: a bound check refuses a missing #member"() {
        when:
        new BoundCheck(executor, release)

        then:
        def refused = thrown(NullPointerException)
        refused.message == member

        where:
        member | executor | release
        'executor' | null | ({ -> } as Runnable)
        'release' | ([:] as Executor) | null
    }
}
