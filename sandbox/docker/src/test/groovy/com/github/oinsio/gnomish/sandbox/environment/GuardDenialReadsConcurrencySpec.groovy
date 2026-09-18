package com.github.oinsio.gnomish.sandbox.environment

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * {@code lock-scope.md}: what {@link GuardDenialReads}'s monitor may and may not be held across.
 * {@link GuardDenialReads#read} drives a {@code docker logs} subprocess bounded by {@code
 * factory.docker-command-timeout} (five minutes by default), so it must run with the monitor
 * released — otherwise a slow or wedged daemon stalls every caller of {@link
 * GuardDenialReads#cursor}, {@link GuardDenialReads#restore} and {@link
 * GuardDenialReads#sourceRecreated} for the whole read, even though none of them touch the log.
 *
 * <p>Real threads and a docker fake that blocks on a latch, since the property under test is
 * exactly the one a single-threaded spec cannot see.
 */
class GuardDenialReadsConcurrencySpec extends Specification {

    private static final long PROMPTLY_SECONDS = 2
    private static final long GENEROUSLY_SECONDS = 30

    private CountDownLatch logsEntered = new CountDownLatch(1)
    private CountDownLatch releaseLogs = new CountDownLatch(1)

    /** A docker fake standing in for a wedged `docker logs`: it blocks only on the logs argv. */
    private RecordingDockerCli blockingOnLogsRead() {
        def docker = new RecordingDockerCli()
        docker.onRun = { List<String> args ->
            if (args == GuardCommands.guardLogs('k1', 1000, null)) {
                logsEntered.countDown()
                releaseLogs.await(GENEROUSLY_SECONDS, TimeUnit.SECONDS)
                return DockerResult.of(0, '', '')
            }
            args == GuardCommands.inspectGuardId('k1') ? DockerResult.of(0, 'sha256:c1', '') : DockerResult.of(0, '', '')
        }
        docker
    }

    // lock-scope.md: cursor() reads in-memory state only; it must not wait out a read() in flight.
    def "cursor() does not wait for an in-flight read()"() {
        given:
        def reads = new GuardDenialReads(blockingOnLogsRead(), 'k1')

        when: 'a read is in flight'
        def reader = Thread.ofPlatform().start { reads.read() }
        logsEntered.await(GENEROUSLY_SECONDS, TimeUnit.SECONDS)

        and: 'another thread asks for the cursor'
        def answered = new CountDownLatch(1)
        Thread.ofPlatform().start {
            reads.cursor()
            answered.countDown()
        }

        then: 'it returns without waiting for the subprocess'
        answered.await(PROMPTLY_SECONDS, TimeUnit.SECONDS)

        cleanup:
        releaseLogs.countDown()
        reader.join()
    }

    // lock-scope.md: sourceRecreated() is a pure in-memory invalidation; same obligation as cursor().
    def "sourceRecreated() does not wait for an in-flight read()"() {
        given:
        def reads = new GuardDenialReads(blockingOnLogsRead(), 'k1')

        when:
        def reader = Thread.ofPlatform().start { reads.read() }
        logsEntered.await(GENEROUSLY_SECONDS, TimeUnit.SECONDS)

        def answered = new CountDownLatch(1)
        Thread.ofPlatform().start {
            reads.sourceRecreated()
            answered.countDown()
        }

        then:
        answered.await(PROMPTLY_SECONDS, TimeUnit.SECONDS)

        cleanup:
        releaseLogs.countDown()
        reader.join()
    }
}
