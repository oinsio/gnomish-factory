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
        given: 'an in-box process that never exits by itself, holding its pipes open, as a hung command does'
        docker.script.stalls = true

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
        docker.script.stalls = true

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
        docker.script.stdout = 'ok'.bytes
        docker.script.stderr = 'head: warning'
        docker.script.exitsOnceStderrRead = true

        when:
        def outcome = completeWithin { channel().readFile('note.txt', 10) }

        then:
        outcome instanceof Optional
        new String(((Optional<byte[]>) outcome).get()) == 'ok'
    }
}
