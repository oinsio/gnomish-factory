package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.time.Duration
import spock.lang.Specification

/**
 * FR19 of fix-terminal-receipt-convergence: a drain keeps the read failure that ended it before
 * the end of the stream, beside the output it captured, for a reader that classifies a broken
 * stream ({@code CapturedExec}); a stream that reached its end leaves none. Driven over
 * {@link FakeProcess}, whose read end fails every query once closed (design D6 of
 * make-pipe-reads-non-parking).
 */
class DrainFailureSpec extends Specification implements DrainSpecSupport {

    private static final Duration BOUND = Duration.ofSeconds(5)

    def "a stream that breaks mid-read leaves its failure, and the prefix stays readable"() {
        given: 'a live child that spoke, and whose read end then breaks'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('head')
        def drain = Drain.start(ChildOutput.stdout(process), 'failure-spec')
        assert process.awaitStdoutRead(BOUND)
        process.inputStream.close()

        when:
        boolean cut = Drain.joinAll(BOUND, drain)

        then:
        !cut
        drain.failure() instanceof IOException
        drain.failure().message == 'Stream Closed'
        drain.captured() == 'head'

        cleanup:
        process.exit(0)
    }

    def "a stream that reaches its end leaves no failure"() {
        given:
        def drain = Drain.start(ChildOutput.stdout(exitedWith('whole')), 'failure-spec')

        when:
        Drain.joinAll(BOUND, drain)

        then:
        drain.failure() == null
        drain.captured() == 'whole'
    }
}
