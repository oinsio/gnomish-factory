package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.time.Duration
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The lifecycle of {@link ProgressLines} (FR5, NFR-R2 of define-executor-contract): a bounded wait
 * for the stream's end, the owner's stop, and an interrupt that is left set for the caller. A live
 * {@link FakeProcess} that never exits is a stream that cannot end on its own, so each feature's
 * outcome holds whatever order the reader thread runs in.
 */
@Timeout(30)
class ProgressLinesLifecycleSpec extends Specification {

    FakeProcess live = FakeProcess.running()

    ProgressLines lines = ProgressLines.start(ChildOutput.stdout(live), new VirtualClock(), 'acme')

    def cleanup() {
        lines.close()
        live.exit(0)
        Thread.interrupted()
    }

    // NFR-R2: a stream that has not ended is not reported ended, with or without a wait
    def "NFR-R2: a live stream is not ended after no wait and after a short one"() {
        expect:
        !lines.endsWithin(Duration.ZERO)
        !lines.endsWithin(Duration.ofMillis(20))
        !lines.ended()
    }

    // NFR-R2: the owner's stop ends the reader, and the wait sees it
    def "NFR-R2: a stopped reader ends, and a wait reports it"() {
        when:
        lines.close()

        then:
        lines.endsWithin(Duration.ofSeconds(10))
        lines.ended()
    }

    // NFR-R2: a reader still reading at its post-exit bound is stopped and reported unfinished
    def "NFR-R2: a reader unfinished at its bound is reported so and stopped"() {
        expect:
        !lines.await(Duration.ofMillis(20))
        lines.endsWithin(Duration.ofSeconds(10))
    }

    // NFR-R2: an interrupt during either wait is left set for the caller, and the reader is not reported ended
    def "NFR-R2: an interrupted #wait keeps the interrupt and does not report the stream ended"() {
        given:
        Thread.currentThread().interrupt()

        when:
        def result = wait == 'endsWithin' ? lines.endsWithin(Duration.ofSeconds(10)) : lines.await(Duration.ofSeconds(10))

        then:
        !result
        Thread.interrupted()

        where:
        wait << ['endsWithin', 'await']
    }

    // NFR-R2: an exited stream is reported ended by the wait
    def "NFR-R2: a stream that ends at the exit is reported ended"() {
        when:
        live.exit(0)

        then:
        lines.endsWithin(Duration.ofSeconds(10))
    }
}
