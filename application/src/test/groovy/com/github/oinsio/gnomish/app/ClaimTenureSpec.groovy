package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import spock.lang.Specification

/**
 * ClaimTenure: the claim beat and the claim-loss flag as one value (FR3 of introduce-slot-wiring).
 * Production builds it only through TakeHeartbeat.tenure() (pinned in TakeHeartbeatSpec); this
 * spec pins the claimless combination the disposition and bare-auto specs pass — ClaimBeat.NONE
 * beside a fresh flag — so that pairing stays a valid tenure.
 *
 * FR3 of introduce-slot-wiring.
 */
class ClaimTenureSpec extends Specification {

    def "FR3: a claimless tenure pairs the no-op beat with a fresh flag that reports no loss"() {
        given:
        def flag = new ClaimLossFlag()

        when:
        def tenure = new ClaimTenure(ClaimBeat.NONE, flag)

        then:
        tenure.beat().is(ClaimBeat.NONE)
        tenure.lossFlag().is(flag)
        !tenure.lossFlag().isLost(new TaskRef('PROJ-1'))
    }
}
