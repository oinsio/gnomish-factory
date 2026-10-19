package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.time.Duration
import spock.lang.Specification

/**
 * FR16, FR17, design D15 of fix-terminal-receipt-convergence; FR4, NFR-R2, design D3, D4 of
 * make-pipe-reads-non-parking: drains are joined under one shared post-exit bound, a drain still
 * open at its end is stopped — never its pipe closed — and the capture is reported cut. Driven over
 * {@link FakeProcess}: a child the spec keeps alive is a drain that cannot end on its own, so no
 * scheduling order is a premise. Each drain answers whether it was the one cut, so a reader can name
 * the stream in its refusal (FR19).
 */
class DrainJoinAllSpec extends Specification implements DrainSpecSupport {

    private static final Duration BOUND = Duration.ofMillis(400)
    private static final Duration GONE = Duration.ofSeconds(10)

    private final List<FakeProcess> processes = []

    def cleanup() {
        processes*.exit(0)
    }

    def "FR16: two open drains cost the bound once, then both are stopped and the capture is cut"() {
        given:
        FakeProcess out = live('out')
        FakeProcess err = live('err')
        Drain stdout = Drain.start(ChildOutput.stdout(out), 'join-all-spec-out')
        Drain stderr = Drain.start(ChildOutput.stdout(err), 'join-all-spec-err')
        awaitKept(out)
        awaitKept(err)

        when:
        long startedAt = System.nanoTime()
        boolean cut = Drain.joinAll(BOUND, stdout, stderr)
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'one shared bound, not one per drain'
        cut
        stdout.cut()
        stderr.cut()
        elapsed >= BOUND
        elapsed <BOUND + Duration.ofMillis(300)

        and: 'both drains were stopped: a second join finds them ended, the children still alive'
        !Drain.joinAll(GONE, stdout, stderr)
        out.alive
        err.alive

        and: 'what each drain read before is kept, and no pipe was closed'
        stdout.captured() == 'out'
        stderr.captured() == 'err'
        out.inputStream.available() == 0
        err.inputStream.available() == 0
    }

    def "FR17: one open drain among ended ones is the one stopped and reported cut (open: #openName)"() {
        given:
        FakeProcess open = live('open')
        FakeProcess ended = exitedWith('ended')
        Drain openDrain = Drain.start(ChildOutput.stdout(open), 'join-all-spec-open')
        Drain endedDrain = Drain.start(ChildOutput.stdout(ended), 'join-all-spec-ended')
        awaitKept(open)
        List<Drain> drains = openFirst ? [openDrain, endedDrain] : [endedDrain, openDrain]

        when:
        boolean cut = Drain.joinAll(BOUND, drains as Drain[])

        then:
        cut

        and: 'each drain says whether it was the one cut, so a reader can name the stream'
        openDrain.cut()
        !endedDrain.cut()
        openDrain.captured() == 'open'
        endedDrain.captured() == 'ended'

        and: 'the open drain was stopped: a second join finds it ended, its child still alive'
        !Drain.joinAll(GONE, openDrain)
        open.alive

        where:
        openFirst << [true, false]
        openName = openFirst ? 'first' : 'second'
    }

    def "NFR-P1: drains that end on their own are not cut, and the join closes no pipe"() {
        given:
        FakeProcess out = exitedWith('out')
        FakeProcess err = exitedWith('err')
        Drain stdout = Drain.start(ChildOutput.stdout(out), 'join-all-spec-out')
        Drain stderr = Drain.start(ChildOutput.stdout(err), 'join-all-spec-err')

        when:
        boolean cut = Drain.joinAll(BOUND, stdout, stderr)

        then:
        !cut
        !stdout.cut()
        !stderr.cut()
        stdout.captured() == 'out'
        stderr.captured() == 'err'
        out.inputStream.available() == 0
        err.inputStream.available() == 0
    }

    private FakeProcess live(String text) {
        FakeProcess process = FakeProcess.running()
        process.writeStdout(text)
        processes << process
        process
    }
}
