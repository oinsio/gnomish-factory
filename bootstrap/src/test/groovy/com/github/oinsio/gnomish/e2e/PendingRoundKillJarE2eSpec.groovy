package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.Timeout

/**
 * M5 and G5 of extract-agent-round-runner (task 7.1; FR11, NFR-R3, NFR-C2, UX3; design D11 as amended
 * 2026-11-25, windows K1 and K2): a {@code program} {@code transform} executor declaring {@code poll}
 * hands the stage's work to a fixture service ({@code [transform-service-holding]}, then {@code
 * [transform-service]}; the service's request log is its count of {@code start}s), the packaged
 * factory in host git mode is killed inside the round's pending wait, and a second packaged factory
 * resumes the task on another clone of the same origin.
 *
 * <p>Each kill is made by the medium, never hoped for: after the pending round commit, the service holds
 * the first poll (a poll follows the push of that commit), and the spec kills the moment the service
 * recorded it; before it, origin's pre-receive hook ({@code hook-hold-pending-push}) holds the push of
 * that commit, and the spec kills the moment the hook reports it. The kill takes the factory and every
 * process it started. Everything is read from the durable media the processes left behind ({@link
 * PendingRoundJarMedia}) — the task branch on the bare origin, the service's log, the second instance's
 * log file, whose round line is a {@code run}'s view of the ledger — never from a process's internals.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class PendingRoundKillJarE2eSpec extends Specification {

    /** The key the service's {@code start} answers ({@code data/executor-result#pending}). */
    static final String KEY = 'reference-deferred'

    /** The key as the round line shows it: its prefix ({@code PendingKeyPrefix}, 16 characters and a mark of the cut). */
    static final String SHOWN = KEY.take(16) + '...'

    static final String PENDING = 'gnomish: pending work#0 '

    private final E2eProcessHarness harness = new E2eProcessHarness()
    private final String task = "e2e-pending-${System.nanoTime() % 1_000_000}"
    private final List<E2eFactoryProcess> launched = []
    private PendingRoundJarMedia media

    def setup() {
        media = PendingRoundJarMedia.published(task, 'transform-service-holding')
    }

    def cleanup() {
        launched.findAll { it.alive() }.each { it.killed() }
    }

    // Scenario: pipeline/stage-engine / Kill after the pending commit resumes the poll
    def "M5: killed after the pending round commit, a second packaged instance polls the service's key, never starts it again, and closes the round with the service's change"() {
        when: 'the first instance runs the task and is killed while the service holds its first poll'
        def first = fresh()
        awaitThat(first, 'the service saw no poll') {
            media.requests().any { it.startsWith('poll ') }
        }
        def dying = first.killed()

        then: 'origin holds the pending round commit with the service\'s key, registered, and the service was started once'
        String pending = media.tip()
        media.subjects()[0].startsWith(PENDING)
        media.registered() == pending
        media.stateAt(pending).pendingPolls()*.key()*.value() == [KEY]
        media.requests() == ['start -', 'poll ' + KEY]
        !dying.stderr().contains('\tat ')

        when: 'the job ends, and a second instance resumes the task on another clone'
        StandIn.repoint(media.service, 'transform-service')
        Path other = media.otherClone()
        def second = resumed(other)

        then: 'the pipeline completed'
        completed(second)

        and: 'the service was polled by its recorded key and never started again'
        media.requests() == [
            'start -',
            'poll ' + KEY,
            'poll ' + KEY
        ]

        and: 'the round closed above the pending round commit with the service\'s change, passing, no pending entry left'
        media.assertClosedAbove(pending)

        and: 'the round line of the second instance shows the round resumed by the key, with no start of its own'
        PendingRoundJarMedia.roundLines(other) == [
            "pendingKey=-, resumedKey=${SHOWN},".toString()
        ]

        and:
        secondRecoveryIsNoOp(other)
    }

    // Scenario: pipeline/stage-engine / Kill before the pending commit re-drives start
    def "M5: killed before the pending round commit, a second packaged instance starts the service again, names that start, and closes the round"() {
        given: 'origin holding any push of a pending commit'
        Path hook = StandIn.link(Files.createDirectories(media.origin.resolve('hooks')).resolve('pre-receive'), 'hook-hold-pending-push')

        when: 'the first instance runs the task and is killed while origin holds the push of its pending round commit'
        def first = fresh()
        awaitThat(first, 'no pending push reached origin') {
            Files.exists(StandIn.beside(hook, 'held'))
        }
        def dying = first.killed()

        then: 'origin holds no pending round commit, and the service was started once and never polled'
        String registered = media.registered()
        media.tip() == registered
        !media.subjects().any { it.startsWith(PENDING) }
        media.stateAt(registered).pendingPolls().isEmpty()
        media.requests() == ['start -']
        !dying.stderr().contains('\tat ')

        when: 'origin takes every push, the job ends, and a second instance resumes the task on another clone'
        StandIn.repoint(hook, 'hook-record')
        StandIn.repoint(media.service, 'transform-service')
        Path other = media.otherClone()
        def second = resumed(other)

        then: 'the pipeline completed'
        completed(second)

        and: 'the service counts two starts — the duplicate a kill before the pending commit costs — and one poll of the new run'
        media.requests() == [
            'start -',
            'start -',
            'poll ' + KEY
        ]

        and: 'the re-driven round went pending on origin before it closed with the service\'s change, passing'
        media.subjects("${registered}..${media.branch()}").any {
            it.startsWith(PENDING)
        }
        media.assertClosedAbove(registered)

        and: 'the round line of the second instance names the re-driven start: pending with its key, resumed by none'
        PendingRoundJarMedia.roundLines(other) == [
            "pendingKey=${SHOWN}, resumedKey=-,".toString()
        ]

        and:
        secondRecoveryIsNoOp(other)
    }

    /** A further resume of the finished task changes nothing: origin's refs and the service's log stay as they were. */
    private void secondRecoveryIsNoOp(Path other) {
        def before = media.footprint()
        assert completed(resumed(other))
        assert media.footprint() == before
    }

    private static boolean completed(E2eProcessResult result) {
        assert result.exitCode() == 0
        assert result.stdout().contains('Stage: pipeline complete')
        assert !result.stderr().contains('\tat ')
        true
    }

    private E2eFactoryProcess fresh() {
        launch(media.clone, [
            '--task=hand the work to the service',
            '--task-id=' + task
        ])
    }

    private E2eProcessResult resumed(Path clone) {
        launch(clone, ['--resume=' + task]).await(Duration.ofMinutes(5))
    }

    private E2eFactoryProcess launch(Path clone, List<String> args) {
        def process = harness.launch('run', clone, ['--dir=' + clone] + args)
        launched << process
        process
    }

    /** Waits until {@code condition} holds; the factory ending first fails with what it wrote. */
    private static void awaitThat(E2eFactoryProcess factory, String failure, Closure<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        while (!condition()) {
            if (!factory.alive()) {
                throw new AssertionError("the factory ended first (${failure}): ${factory.await(Duration.ZERO)}")
            }
            assert System.nanoTime() <deadline: "${failure} within 5 minutes"
            Thread.sleep(200)
        }
    }
}
