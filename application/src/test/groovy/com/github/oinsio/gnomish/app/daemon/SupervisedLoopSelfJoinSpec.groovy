package com.github.oinsio.gnomish.app.daemon

import ch.qos.logback.classic.Level
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.Timeout

/**
 * A join requested from the loop's own worker (design D4 of
 * supervise-daemon-loops-and-embed-dashboard): a thread cannot outlive itself, so such a join is
 * refused at once instead of waiting forever — and a refusal, not a return, because returning
 * would claim that no tick follows while the caller is that tick.
 */
@Timeout(10)
class SupervisedLoopSelfJoinSpec extends Specification {

    private final SupervisedLoopHarness rig = new SupervisedLoopHarness(Level.INFO)

    def cleanup() {
        rig.close()
    }

    // FR4: stopAndJoin from a tick still requests the stop, and refuses the join it cannot finish.
    def "stopAndJoin from the loop's own tick is refused, and the stop it requested still ends the loop"() {
        given:
        Throwable refused = null
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait()) { n ->
            try {
                rig.loop.stopAndJoin()
            } catch (IllegalStateException e) {
                refused = e
            }
            rig.done.countDown()
        }

        when:
        rig.loop.start()

        then:
        rig.done.await(SupervisedLoopHarness.JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS)
        refused != null
        rig.stopAndJoinWithinBound()
        rig.journal == ['tick1']
    }

    // FR3: awaitEnd from a tick is refused; the loop is not stopped by the refusal.
    def "awaitEnd from the loop's own tick is refused and leaves the loop running"() {
        given:
        Throwable refused = null
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait()) { n ->
            if (n == 1) {
                try {
                    rig.loop.awaitEnd()
                } catch (IllegalStateException e) {
                    refused = e
                }
            } else {
                rig.stopHere()
            }
        }

        when:
        rig.runToStop()

        then:
        refused != null
        rig.journal == ['tick1', 'wait PT1M', 'tick2']
    }
}
