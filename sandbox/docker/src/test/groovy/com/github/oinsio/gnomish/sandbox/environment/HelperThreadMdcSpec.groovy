package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.slf4j.MDC
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * The virtual-thread hops this module makes to read or feed a subprocess pipe — {@link
 * ChildProcessStdin}'s stdin pump and {@link ExecPipeDrain}'s exec-pipe drain — emit their lines
 * from a thread the round never touches, so without the {@code MdcAwareThread} frame those lines
 * land with an empty MDC. That is the failure this spec exists for: the lines describing what a
 * task's own process said would be exactly the ones a {@code grep taskId=} misses
 * ({@code StreamDrainSpec} is the precedent for the same claim on the agent-stdout drain).
 *
 * <p>Both sites log only on the pipe-failure path, so each scenario hands the site a stream that
 * fails immediately — the log line is the observable, and its MDC map is the assertion.
 *
 * <p>Implements FR8 of harden-logging-observability.
 */
class HelperThreadMdcSpec extends Specification {

    LogCaptureSupport logs

    def cleanup() {
        logs?.detach()
        MDC.clear()
    }

    /** A process whose stdin is already broken, so the pump's catch runs on the helper thread. */
    private static Process processWithBrokenStdin(CountDownLatch wrote) {
        new Process() {

                    @Override
                    OutputStream getOutputStream() {
                        new OutputStream() {

                                    @Override
                                    void write(int b) throws IOException {
                                        throw new IOException('child closed stdin')
                                    }

                                    @Override
                                    void close() {
                                        wrote.countDown()
                                    }
                                }
                    }

                    @Override
                    InputStream getInputStream() {
                        InputStream.nullInputStream()
                    }

                    @Override
                    InputStream getErrorStream() {
                        InputStream.nullInputStream()
                    }

                    @Override
                    int waitFor() {
                        0
                    }

                    @Override
                    int exitValue() {
                        0
                    }

                    @Override
                    void destroy() {}
                }
    }

    def "FR8: the stdin pump's line lands in the round's task scope"() {
        given:
        logs = LogCaptureSupport.attach(ChildProcessStdin, Level.DEBUG)
        MDC.put('taskId', 'T-7')
        MDC.put('stage', 'implement')
        def wrote = new CountDownLatch(1)

        when: 'the pump is spawned from the round thread and its write fails on the helper thread'
        ChildProcessStdin.feed(processWithBrokenStdin(wrote), 'a prompt')
        wrote.await(10, TimeUnit.SECONDS)

        then: 'the helper thread emitted under the scope the round handed it'
        new PollingConditions(timeout: 10).eventually {
            assert logs.list.size() == 1
            assert logs.list[0].MDCPropertyMap['taskId'] == 'T-7'
            assert logs.list[0].MDCPropertyMap['stage'] == 'implement'
        }
    }

    def "FR8: the exec pipe drain's line lands in the round's task scope"() {
        given:
        logs = LogCaptureSupport.attach(ExecPipeDrain, Level.DEBUG)
        MDC.put('taskId', 'T-7')
        def broken = new InputStream() {

                    @Override
                    int read() throws IOException {
                        throw new IOException('pipe broke mid-exec')
                    }
                }

        when: 'the drain is started from the round thread and the pipe fails on the helper thread'
        ExecPipeDrain.start(broken, 'channel-read-stdout').join()

        then:
        logs.list.size() == 1
        logs.list[0].MDCPropertyMap['taskId'] == 'T-7'
    }

    def "FR8: a helper thread leaves no context behind for the next body on its carrier"() {
        given:
        logs = LogCaptureSupport.attach(ExecPipeDrain, Level.DEBUG)
        MDC.put('taskId', 'T-7')
        def broken = new InputStream() {

                    @Override
                    int read() throws IOException {
                        throw new IOException('pipe broke mid-exec')
                    }
                }

        when:
        ExecPipeDrain.start(broken, 'channel-read-stdout').join()

        then: 'the spawning thread keeps its own scope — the frame clears the helper\'s, not this one\'s'
        MDC.get('taskId') == 'T-7'
    }
}
