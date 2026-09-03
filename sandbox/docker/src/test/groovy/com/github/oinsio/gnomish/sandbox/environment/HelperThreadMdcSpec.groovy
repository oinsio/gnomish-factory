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
 * ChildProcessStdin}'s stdin pump, {@link ExecPipeDrain}'s exec-pipe drain, and {@link
 * ContainerFileChannel}'s in-box write pump — emit their lines
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

    def "FR8: the in-box write pump's line lands in the round's task scope"() {
        given:
        logs = LogCaptureSupport.attach(ContainerFileChannel, Level.DEBUG)
        MDC.put('taskId', 'T-7')
        MDC.put('stage', 'implement')
        def docker = new BrokenStdinFileChannelDockerCli()

        when: 'the channel is driven from the round thread and its stdin fails on the pump thread'
        new ContainerFileChannel(docker, 'k1', '/gnomish/work', '/gnomish/scratch')
                .putFile('notes.txt', 'a prompt'.bytes)

        then: 'the pump emitted under the scope the round handed it'
        logs.list.size() == 1
        logs.list[0].formattedMessage.contains('closed stdin early')
        logs.list[0].MDCPropertyMap['taskId'] == 'T-7'
        logs.list[0].MDCPropertyMap['stage'] == 'implement'
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

/**
 * A {@link DockerCli} whose exec'd process refuses every stdin write, so {@code
 * ContainerFileChannel}'s pump takes its {@code IOException} catch — the one line that helper
 * thread ever emits, and therefore the only observable of its MDC frame.
 */
class BrokenStdinFileChannelDockerCli extends DockerCli {

    BrokenStdinFileChannelDockerCli() {
        super('docker')
    }

    @Override
    DockerResult run(List<String> args) {
        throw new IllegalStateException('run() not expected: ' + args)
    }

    @Override
    Process start(List<String> args, boolean mergeStderr) {
        new BrokenStdinProcess()
    }
}

/** An exited process with an empty stdout and a stdin that throws on first write. */
class BrokenStdinProcess extends FakeProcess {

    @Override
    OutputStream getOutputStream() {
        new OutputStream() {

                    @Override
                    void write(int b) throws IOException {
                        throw new IOException('child closed stdin')
                    }
                }
    }

    @Override
    InputStream getInputStream() {
        new ByteArrayInputStream(new byte[0])
    }

    @Override
    int waitFor() {
        0
    }

    @Override
    int exitValue() {
        0
    }
}
