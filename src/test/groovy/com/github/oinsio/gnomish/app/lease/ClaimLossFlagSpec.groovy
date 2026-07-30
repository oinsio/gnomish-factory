package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * ClaimLossFlag, the cross-thread hand-off for a lost claim (design D7, FR8): the beat thread
 * records a lost claim through the {@link ClaimLostSink} seam, the take run polls it at each
 * round boundary. These specs pin the flag's contract — records, reads back, latches, is
 * keyed per task, and is safe under concurrent set/read.
 *
 * FR8 of add-claim-heartbeat.
 */
class ClaimLossFlagSpec extends Specification {

    private static final TaskRef A = new TaskRef('github:o/r#1')
    private static final TaskRef B = new TaskRef('github:o/r#2')

    private final ClaimLossFlag flag = new ClaimLossFlag()

    // FR8: a fresh flag reports no claim lost — the run proceeds while nothing is flagged.
    def "a fresh flag reports no claim lost"() {
        expect:
        !flag.isLost(A)
    }

    // FR8: claimLost records the loss so a later boundary poll reads it back as lost.
    def "claimLost records the loss and isLost reads it back"() {
        when:
        flag.claimLost(A)

        then:
        flag.isLost(A)
    }

    // FR8: the flag is keyed per task — a loss for one claim never flags another.
    def "a loss for one task does not flag another"() {
        when:
        flag.claimLost(A)

        then:
        flag.isLost(A)
        !flag.isLost(B)
    }

    // FR8: recording is idempotent and latching — a claim once gone stays flagged.
    def "the flag latches: a second claimLost leaves it set"() {
        when:
        flag.claimLost(A)
        flag.claimLost(A)

        then:
        flag.isLost(A)
    }

    // FR8: a beat thread may set the flag while the engine thread reads it — the set is
    //     always eventually visible to the reader across threads.
    def "a concurrent set is visible to a reader on another thread"() {
        given:
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        def start = new CountDownLatch(1)

        when: 'one thread sets the flag while another awaits it'
        def setter = executor.submit({
            start.await()
            flag.claimLost(A)
        })
        start.countDown()
        setter.get(2, TimeUnit.SECONDS)

        then: 'the reader on this thread observes the loss'
        flag.isLost(A)

        cleanup:
        executor.shutdownNow()
    }
}
