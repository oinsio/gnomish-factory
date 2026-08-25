package com.github.oinsio.gnomish.sandbox.environment

import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR2, FR11 of bound-subprocess-commands: the file channel must read the exec
 * pipes concurrently with the supervised wait, never to EOF ahead of it.
 *
 * <p>A hung in-box process holds its stdout open, so a read-to-EOF on the calling
 * thread is entered before the supervisor ever sees the process — and a stream
 * over an OS pipe is not interruptible, so the named interrupt outcome this class
 * promises could never be reached. An undrained stderr is the mirror defect: the
 * in-box process cannot exit while a pipe nobody reads has filled.
 */
class ContainerFileChannelStallSpec extends Specification {

    ScriptedFileChannelDockerCli docker = new ScriptedFileChannelDockerCli()

    def cleanup() {
        Thread.interrupted() // never leak an interrupt flag into the next feature
    }

    private ContainerFileChannel channel() {
        new ContainerFileChannel(docker, 'k1', '/gnomish/work', '/gnomish/scratch')
    }

    /**
     * Runs {@code body} on a thread of its own, bounded, so a channel that wedges
     * fails the feature instead of hanging the build.
     */
    private static Object completeWithin(Closure body) {
        def outcome = new Object[1]
        def done = new CountDownLatch(1)
        def runner = new Thread({
            try {
                outcome[0] = body()
            } catch (Throwable failure) {
                outcome[0] = failure
            } finally {
                done.countDown()
            }
        })
        runner.setDaemon(true)
        runner.start()
        boolean finished = done.await(10, TimeUnit.SECONDS)
        runner.interrupt()
        assert finished: 'the channel operation never returned: an exec pipe blocked ahead of the supervised wait'
        return outcome[0]
    }

    // FR11: an in-box read whose stdout never reaches EOF still ends as the named interruption
    def "an interrupted readFile of a stalled exec still names the interruption"() {
        given: 'an in-box process holding its stdout open, as a hung command does'
        docker.process.stdoutStream = new StalledInputStream()

        when:
        def outcome = completeWithin {
            Thread.currentThread().interrupt()
            channel().readFile('note.txt', 10)
        }

        then:
        outcome instanceof UncheckedIOException
        outcome.cause instanceof InterruptedIOException
    }

    // FR11: the same on the write path — the write's own stdout pipe must not gate the wait
    def "an interrupted putFile of a stalled exec still names the interruption"() {
        given:
        docker.process.stdoutStream = new StalledInputStream()

        when:
        def outcome = completeWithin {
            Thread.currentThread().interrupt()
            channel().putFile('note.txt', 'x'.bytes)
        }

        then:
        outcome instanceof UncheckedIOException
        outcome.cause instanceof InterruptedIOException
    }

    // FR2: stderr is an exec pipe too — a read that never drains it cannot let the exec exit
    def "a read whose in-box command writes to stderr completes"() {
        given: 'an exec that cannot exit until its stderr has been consumed'
        docker.process.stdout = 'ok'.bytes
        docker.process.stderrStream = new ByteArrayInputStream('head: warning'.bytes)
        docker.process.exitsOnlyWhenStderrDrained = true

        when:
        def outcome = completeWithin { channel().readFile('note.txt', 10) }

        then:
        outcome instanceof Optional
        new String(((Optional<byte[]>) outcome).get()) == 'ok'
    }

    // FR11: an interrupt landing on a still-running drain's join is restored for callers up the
    // stack — the drain must not swallow the flag the supervisor deliberately left set
    def "an interrupt landing on the exec pipe drain join is restored"() {
        given: 'a wait that leaves the flag set, and a stdout pipe still open when the join begins'
        docker.process.stdoutStream = new SlowEofStream()
        docker.process.interruptOnWaitFor = true

        when:
        channel().readFile('note.txt', 10)

        then:
        Thread.interrupted() // asserts the flag was restored (and clears it)
    }
}

/** An exec pipe still open when the join begins, reaching EOF shortly after. */
class SlowEofStream extends InputStream {

    private final CountDownLatch never = new CountDownLatch(1)

    @Override
    int read() {
        never.await(1, TimeUnit.SECONDS)
        return -1
    }
}

/** An exec pipe that never reaches EOF and never yields a byte — a hung in-box command. */
class StalledInputStream extends InputStream {

    private final CountDownLatch never = new CountDownLatch(1)

    @Override
    int read() {
        never.await() // an in-JVM stand-in for a pipe read that simply never returns
        return -1
    }
}
