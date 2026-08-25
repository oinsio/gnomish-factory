package com.github.oinsio.gnomish.gitobjects

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import spock.lang.Specification

/**
 * FR25: the pump threads {@link GitExecStreams} spins up around a subprocess — {@code feed} writing stdin,
 * {@code drain} reading stderr — must actually be started, marked daemon (never block JVM exit), and
 * joined by {@code await} before the result is handed back (otherwise a caller could observe a
 * partially-drained stream).
 */
class GitExecPumpThreadsSpec extends Specification {

    // FR25: feed() both starts its thread and marks it daemon
    def "FR25: feed starts a daemon thread that writes the given bytes to the process and closes stdin"() {
        given: 'a process that echoes whatever it reads from stdin back on stdout'
        def process = new ProcessBuilder('cat').start()
        byte[] payload = 'hello-stdin'.getBytes('UTF-8')

        when: 'feed is invoked through reflection (it is a private implementation detail)'
        def method = GitExecStreams.getDeclaredMethod('feed', Process, byte[])
        method.accessible = true
        Object[] args = new Object[2]
        args[0] = process
        args[1] = payload
        Thread thread = method.invoke(null, args) as Thread

        then: 'the feeder thread never blocks JVM shutdown'
        thread.daemon

        when: 'cat only exits once its stdin has been fully written and closed by the feeder thread'
        boolean finished = process.waitFor(5, TimeUnit.SECONDS)
        // A mutant that drops the feeder's stdin-close leaves `cat` blocked forever waiting for
        // more input, which would make an unbounded readAllBytes() below hang right along with
        // it — force the process down first so the read is always bounded, mutant or not.
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        byte[] output = process.inputStream.readAllBytes()

        then: 'the thread actually ran — the bytes reached the other side of the pipe'
        finished
        output == payload

        cleanup:
        process.destroyForcibly()
    }

    // FR25: drain() both starts its thread and marks it daemon
    def "FR25: drain starts a daemon thread that reads the whole stream into the sink"() {
        given:
        def text = 'stderr diagnostic text\nsecond line'
        def stream = new ByteArrayInputStream(text.getBytes('UTF-8'))
        def sink = new StringBuilder()

        when: 'drain is invoked through reflection (it is a private implementation detail)'
        def method = GitExecStreams.getDeclaredMethod('drain', InputStream, StringBuilder)
        method.accessible = true
        Object[] args = new Object[2]
        args[0] = stream
        args[1] = sink
        Thread thread = method.invoke(null, args) as Thread
        thread.join(5_000)

        then: 'the thread actually ran and drained everything into the sink'
        sink.toString() == text

        and: 'the drain thread never blocks JVM shutdown'
        thread.daemon
    }

    // FR25: await() blocks on the stdin pump thread finishing, not just on the process exiting
    def "FR25: await blocks until the stdin pump thread has finished, not merely until the process exits"() {
        given: 'a process that exits almost immediately'
        def process = new ProcessBuilder('true').start()

        and: 'an already-finished stderr thread and a stdin thread still running its work'
        def stderrThread = finishedThread()
        def stdinDone = new AtomicBoolean(false)
        def stdinThread = new Thread({
            Thread.sleep(300)
            stdinDone.set(true)
        } as Runnable)
        stdinThread.start()

        when:
        GitExec.await(process, stdinThread, stderrThread)

        then: 'await did not return before the stdin thread finished its slow work'
        stdinDone.get()
    }

    // FR25: await() blocks on the stderr pump thread finishing, not just on the process exiting
    def "FR25: await blocks until the stderr pump thread has finished, not merely until the process exits"() {
        given: 'a process that exits almost immediately'
        def process = new ProcessBuilder('true').start()

        and: 'an already-finished stdin thread and a stderr thread still running its work'
        def stdinThread = finishedThread()
        def stderrDone = new AtomicBoolean(false)
        def stderrThread = new Thread({
            Thread.sleep(300)
            stderrDone.set(true)
        } as Runnable)
        stderrThread.start()

        when:
        GitExec.await(process, stdinThread, stderrThread)

        then: 'await did not return before the stderr thread finished its slow work'
        stderrDone.get()
    }

    // FR13: readCappedThenAwaitOnFailure still runs await's cleanup when readCapped itself fails
    def "FR13: readCappedThenAwaitOnFailure still joins the pump threads when the capped read fails"() {
        given: 'a process producing at least one byte of stdout, so the capped read gets far enough to hit the interrupt check'
        def process = new ProcessBuilder('echo', 'hi').start()

        and: 'a finished stderr pump and a stdin pump still doing slow work'
        def stderrThread = finishedThread()
        def stdinDone = new AtomicBoolean(false)
        def stdinThread = new Thread({
            Thread.sleep(300)
            stdinDone.set(true)
        } as Runnable)
        stdinThread.start()

        when: 'the calling thread is interrupted so the capped read fails, and readCappedThenAwaitOnFailure is invoked through reflection (it is a private implementation detail)'
        Thread.currentThread().interrupt()
        def method = GitExec.getDeclaredMethod('readCappedThenAwaitOnFailure', Process, Thread, Thread, long)
        method.accessible = true
        method.invoke(null, process, stdinThread, stderrThread, 1L)

        then: 'the read failure surfaces, wrapped by reflection'
        InvocationTargetException ex = thrown(InvocationTargetException)
        GitObjectsException failure = ex.cause as GitObjectsException

        and: 'the cleanup await ran and itself failed — the still-set interrupt flag makes the alive stdin pump\'s join() raise immediately — and that second failure was not swallowed'
        failure.suppressed.length == 1
        failure.suppressed[0] instanceof GitObjectsException

        and: 'the slow stdin pump never got the chance to finish naturally, confirming the join really was interrupted rather than completed'
        !stdinDone.get()

        cleanup:
        Thread.interrupted() // clear the flag so it does not leak into later tests
        stdinThread.join()
        process.waitFor(5, TimeUnit.SECONDS) || process.destroyForcibly()
    }

    private static Thread finishedThread() {
        def thread = new Thread({} as Runnable)
        thread.start()
        thread.join()
        thread
    }
}
