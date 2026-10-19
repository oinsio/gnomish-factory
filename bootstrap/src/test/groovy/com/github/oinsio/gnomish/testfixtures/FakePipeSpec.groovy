package com.github.oinsio.gnomish.testfixtures

import com.github.oinsio.gnomish.sandbox.environment.FakePipe
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR7 of make-pipe-reads-non-parking (design D6): {@link FakePipe}, the one in-memory double of a
 * child's pipe behind {@link FakeProcess}, behaves as a Linux pipe — a close of the read end or an
 * interrupt releases no parked reader, {@code available()} is exact, only the write end's close
 * yields end-of-file.
 *
 * <p>Lives in {@code :bootstrap} because {@code :test-fixtures} has no test source set. "Parked" is
 * observed, never assumed: each feature waits for the reader thread to be in its wait before it acts.
 */
class FakePipeSpec extends Specification {

    private static final Duration BOUND = Duration.ofSeconds(10)
    private static final Duration STILL_PARKED = Duration.ofMillis(300)

    // FR7: the strictness that makes a parked reader visible on macOS as on Linux
    // Scenario: lifecycle/subprocess-supervision / The fake process behaves as a Linux pipe
    def "FR7: a reader parked on a held pipe of the fake process stays parked after the read end's close"() {
        given: 'a live process whose stdout a descendant holds past the exit'
        def process = FakeProcess.running()
        process.holdOpen()
        process.exit(0)
        def stream = process.inputStream

        and: 'a reader parked in a blocking read of it'
        def reader = parkedReader(stream)

        when:
        stream.close()

        then: 'the close released nothing'
        stillParked(reader)

        when: 'the holder lets go'
        process.release()

        then: 'the parked read returns end-of-file'
        reader.result.get(BOUND.toSeconds(), TimeUnit.SECONDS) == -1
    }

    def "FR7: an interrupt releases no parked reader either, and the flag is kept"() {
        given:
        def pipe = new FakePipe()
        def reader = parkedReader(pipe.source())

        when:
        reader.thread.interrupt()

        then:
        stillParked(reader)

        when:
        pipe.write('x'.getBytes(StandardCharsets.UTF_8))

        then: 'the write wakes it, and the interrupt it absorbed is restored'
        reader.result.get(BOUND.toSeconds(), TimeUnit.SECONDS) == ('x' as char) as int
        reader.interrupted.get(BOUND.toSeconds(), TimeUnit.SECONDS)
    }

    // Scenario: lifecycle/subprocess-supervision / The fake process behaves as a Linux pipe
    def "FR7: available() is the exact unread count, through writes, reads and the release"() {
        given:
        def pipe = new FakePipe()
        def source = pipe.source()
        byte[] buffer = new byte[8]

        expect:
        source.available() == 0

        when:
        pipe.write('abc'.bytes)

        then:
        source.available() == 3
        source.read(buffer, 0, 2) == 2
        new String(buffer, 0, 2, StandardCharsets.UTF_8) == 'ab'
        source.available() == 1

        when:
        pipe.write('de'.bytes)
        pipe.release()

        then: 'the release discards nothing'
        source.available() == 3
        source.read(buffer, 0, 8) == 3
        new String(buffer, 0, 3, StandardCharsets.UTF_8) == 'cde'
        source.available() == 0
        source.read() == -1
        source.read(buffer, 0, 8) == -1
    }

    def "FR7: release() yields end-of-file to an empty pipe, and a write after it is refused"() {
        given:
        def pipe = new FakePipe()

        when:
        pipe.release()
        pipe.release()

        then:
        pipe.source().read() == -1

        when:
        pipe.write('late'.bytes)

        then:
        thrown(IllegalStateException)
    }

    def "FR7: a closed read end refuses a later read and query, as FileInputStream does"() {
        given:
        def pipe = new FakePipe()
        pipe.write('x'.bytes)
        pipe.source().close()

        when:
        pipe.source().available()

        then:
        thrown(IOException)

        when:
        pipe.source().read()

        then:
        thrown(IOException)
    }

    def "FR7: a zero-length read returns at once, and a read signals the first bytes it returned"() {
        given:
        def pipe = new FakePipe()

        expect:
        pipe.source().read(new byte[1], 0, 0) == 0
        !pipe.awaitRead(Duration.ZERO)

        when:
        pipe.write('y'.bytes)

        then:
        pipe.source().read() == ('y' as char) as int
        pipe.awaitRead(Duration.ZERO)
    }

    def "the canned fake process reads its output to end-of-file"() {
        given:
        def process = new FakeProcess(3, 'canned')

        expect:
        !process.alive
        process.exitValue() == 3
        process.inputStream.text == 'canned'
        process.errorStream.read() == -1
    }

    private static boolean stillParked(ParkedReader reader) {
        reader.thread.join(STILL_PARKED.toMillis())
        reader.thread.alive && !reader.result.isDone()
    }

    private static ParkedReader parkedReader(InputStream stream) {
        def result = new CompletableFuture<Integer>()
        def interrupted = new CompletableFuture<Boolean>()
        def thread = Thread.ofPlatform().daemon().start {
            try {
                result.complete(stream.read())
            } catch (Throwable failure) {
                result.completeExceptionally(failure)
            } finally {
                interrupted.complete(Thread.currentThread().isInterrupted())
            }
        }
        long deadline = System.nanoTime() + BOUND.toNanos()
        while (thread.state != Thread.State.WAITING) {
            assert System.nanoTime() <deadline: 'the reader never parked'
            Thread.onSpinWait()
        }
        new ParkedReader(thread, result, interrupted)
    }

    private record ParkedReader(Thread thread, CompletableFuture<Integer> result, CompletableFuture<Boolean> interrupted) {
    }
}
