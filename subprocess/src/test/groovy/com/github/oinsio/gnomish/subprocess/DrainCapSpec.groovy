package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.time.Duration
import spock.lang.Specification

/**
 * FR19, design D17 of fix-terminal-receipt-convergence: a drain may keep at most a capacity of the
 * bytes it reads — the shape {@code GitExec}'s stdout cap is applied on — while still reading the
 * stream to its end, so the child never blocks on a full pipe, and it reports whether the stream
 * carried more than it kept. The cap's policy (what a value means, what an overflow means) stays
 * with the caller; the drain only counts. Driven over {@link FakeProcess} (design D6 of
 * make-pipe-reads-non-parking).
 */
class DrainCapSpec extends Specification implements DrainSpecSupport {

    private static final Duration BOUND = Duration.ofSeconds(10)

    def "FR19: a capacity of #capacity over #length bytes keeps #kept bytes and overflowed is #overflowed"() {
        given:
        String content = 'x' * length
        FakeProcess process = exitedWith(content)

        when:
        Drain drain = Drain.capped(ChildOutput.stdout(process), 'drain-cap-spec', capacity)
        boolean cut = Drain.joinAll(BOUND, drain)

        then: 'the drain ended at the end of the stream, having read all of it'
        !cut
        process.inputStream.available() == 0

        and: 'it kept a prefix of the capacity and said whether more arrived'
        drain.bytes() == content.bytes[0..<kept] as byte[]
        drain.overflowed() == overflowed

        where:
        capacity | length || kept | overflowed
        0 | 0 || 0 | false
        0 | 1 || 0 | true
        5 | 3 || 3 | false
        5 | 5 || 5 | false
        5 | 6 || 5 | true
        8192 | 20_000 || 8192 | true
    }

    def "FR19: an uncapped drain keeps every byte and never overflows"() {
        given:
        String content = 'y' * 20_000

        when:
        Drain drain = Drain.start(ChildOutput.stdout(exitedWith(content)), 'drain-cap-spec-uncapped')
        Drain.joinAll(BOUND, drain)

        then:
        drain.bytes() == content.bytes
        drain.captured() == content
        !drain.overflowed()
    }

    def "FR19: a negative capacity is refused rather than read as no cap"() {
        when:
        Drain.capped(ChildOutput.stdout(new FakeProcess()), 'drain-cap-spec-negative', -1)

        then:
        thrown(IllegalArgumentException)
    }
}
