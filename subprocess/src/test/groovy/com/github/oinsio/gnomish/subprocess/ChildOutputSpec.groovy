package com.github.oinsio.gnomish.subprocess

import static com.github.oinsio.gnomish.subprocess.ChildOutputRun.Form.PUSH
import static com.github.oinsio.gnomish.subprocess.ChildOutputRun.Form.VIEW

import com.github.oinsio.gnomish.sandbox.environment.FakePipe
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.time.Duration
import spock.lang.Specification

/**
 * FR1, FR3, NFR-R1 of make-pipe-reads-non-parking (design D1, D2): a child's output is minted from
 * the process, ends at the first query that finds the exit with nothing pending, with no tick after it
 * and every byte written before the exit captured, and reports a failing stream as the reading's failure — the same steps in the
 * push form and in the stream view, so every feature runs through both. The cadence while the child
 * runs is {@code ChildOutputTickSpec}'s. Driven over
 * {@link FakeProcess}, whose pipe behaves as a Linux pipe and counts the reader's queries and reads;
 * the ticks are recorded rather than slept, and every step a feature needs is placed on the reading
 * thread itself, so no scheduling order is a premise.
 */
class ChildOutputSpec extends Specification {

    private static final Duration BOUND = Duration.ofSeconds(10)

    private final FakeProcess process = FakeProcess.running()
    private final FakePipe pipe = process.stdoutPipe()
    private final RecordedTicks ticks = new RecordedTicks(pipe)
    private final ChildOutput output = new ChildOutput(process, process.inputStream, ticks)

    def cleanup() {
        output.stop()
        process.release()
    }

    def "FR3: bytes written before an exit that lands between the liveness check and the query are all captured (#form)"() {
        given: 'the child writes its last bytes and exits just after the reader asked whether it lives'
        pipe.onQuery {
            process.writeStdout('before the exit\n')
            process.exit(0)
        }

        when:
        def run = ChildOutputRun.start(form, output)

        then:
        run.join(BOUND)
        run.captured() == 'before the exit\n'
        run.ended()
        ticks.pauses().isEmpty()
        pipe.queries() == 2

        where:
        form << [PUSH, VIEW]
    }

    // Scenario: lifecycle/subprocess-supervision / The drain ends on its own after the exit
    def "FR3: after the exit with a held pipe and nothing pending, the reading ends at the first query that sees it, with no tick (#form)"() {
        given: 'a descendant holds the pipe past the exit, and the child said its last words before it'
        process.holdOpen()
        process.writeStdout('last words\n')
        process.exit(0)

        when:
        def run = ChildOutputRun.start(form, output)

        then: 'one read, one more query that finds nothing, and the end, with no tick awaited'
        run.join(BOUND)
        run.captured() == 'last words\n'
        run.ended()
        ticks.pauses().isEmpty()
        pipe.queries() == 2
        pipe.reads() == 1
        process.onExit().isDone()

        and: 'nothing was closed: the read end still answers, the holder can still write'
        process.inputStream.available() == 0
        process.writeStdout('late')

        where:
        form << [PUSH, VIEW]
    }

    def "NFR-R1: a stream that fails is reported as the reading's failure, with no end and no tick (#form)"() {
        given: 'the read end is closed, so the next query of it fails'
        process.inputStream.close()

        when:
        def run = ChildOutputRun.start(form, output)

        then:
        run.join(BOUND)
        run.failure() instanceof IOException
        run.failure().message == 'Stream Closed'
        !run.ended()
        ticks.pauses().isEmpty()

        where:
        form << [PUSH, VIEW]
    }

    def "FR1: each minted value reads its own pipe of the child (#form)"() {
        given:
        process.writeStdout('out')
        process.writeStderr('err')
        process.exit(0)

        when:
        def stdout = ChildOutputRun.start(form, ChildOutput.stdout(process))
        def stderr = ChildOutputRun.start(form, ChildOutput.stderr(process))

        then:
        stdout.join(BOUND)
        stderr.join(BOUND)
        stdout.captured() == 'out'
        stderr.captured() == 'err'
        stdout.ended()
        stderr.ended()

        where:
        form << [PUSH, VIEW]
    }

    def "FR1: the view keeps the stream contract for a single byte and an empty read"() {
        given:
        process.writeStdout('é')
        process.exit(0)
        def view = output.stream()

        expect: 'an empty read returns at once without a query'
        view.read(new byte[4], 0, 0) == 0
        pipe.queries() == 0

        and: 'single bytes come back unsigned, then the end'
        view.read() == 0xc3
        view.read() == 0xa9
        view.read() == -1
        output.stream().is(view)
    }
}
