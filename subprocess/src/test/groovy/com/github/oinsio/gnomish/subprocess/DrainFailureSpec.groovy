package com.github.oinsio.gnomish.subprocess

import java.time.Duration
import spock.lang.Specification

/**
 * FR19 of fix-terminal-receipt-convergence: a drain keeps the read failure that ended it before
 * end-of-file, beside the output it captured, for a reader that classifies a broken stream
 * ({@code CapturedExec}); a stream that reached its end leaves none.
 */
class DrainFailureSpec extends Specification {

    private static final Duration BOUND = Duration.ofSeconds(5)

    def "a stream that breaks mid-read leaves its failure, and the prefix stays readable"() {
        given:
        def broken = new IOException('pipe broke')
        def stream = new SequenceInputStream(new ByteArrayInputStream('head'.bytes), new InputStream() {
                    @Override
                    int read() {
                        throw broken
                    }
                })
        def drain = Drain.start(stream, 'failure-spec')

        when:
        boolean cut = Drain.joinAll(BOUND, drain)

        then:
        !cut
        drain.failure().is(broken)
        drain.captured() == 'head'
    }

    def "a stream that reaches its end leaves no failure"() {
        given:
        def drain = Drain.start(new ByteArrayInputStream('whole'.bytes), 'failure-spec')

        when:
        Drain.joinAll(BOUND, drain)

        then:
        drain.failure() == null
        drain.captured() == 'whole'
    }
}
