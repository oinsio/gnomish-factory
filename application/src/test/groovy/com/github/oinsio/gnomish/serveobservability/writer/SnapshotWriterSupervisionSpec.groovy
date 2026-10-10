package com.github.oinsio.gnomish.serveobservability.writer

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.daemon.SupervisedLoop
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import com.github.oinsio.gnomish.status.DaemonComponent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * The snapshot writer as a supervised daemon loop (task 5.1 of
 * supervise-daemon-loops-and-embed-dashboard, design D1–D3, D5): a write cycle that throws past
 * its own catches — an {@code Error} — ends the worker, logged as {@code DAEMON_LOOP_WORKER_DIED}
 * with {@code component=snapshot}, and the respawned writer writes again after one backoff on
 * virtual time; a stray interrupt of the
 * writer's thread is absorbed and the next write waits out a full timer period (serve-observability
 * "Interrupted writer does not spin").
 *
 * <p>Implements FR6 of supervise-daemon-loops-and-embed-dashboard.
 */
@Timeout(10)
class SnapshotWriterSupervisionSpec extends Specification {

    @TempDir
    Path tempDir

    // The loop reports its failures on its own logger (design D6), framed as the writer.
    def loopLogs = LogCaptureSupport.attach(SupervisedLoop)
    SnapshotWriter writer

    def cleanup() {
        writer?.stop()
        loopLogs.detach()
    }

    private List events(OperatorEvent event) {
        (loopLogs.list.toArray() as List).findAll {
            it != null && it.formattedMessage.startsWith(event.head())
        }
    }

    // FR6, D2 as amended, D5 (M4): the write cycle catches every failure of its own steps, so only
    //     an Error reaches the loop — which the old RuntimeException guard let kill the thread
    //     silently. Now the death is one ERROR line, and the respawned writer writes again after
    //     one backoff on virtual time, with no transition marking it dirty.
    def "a write cycle that throws an Error ends the worker, and the respawned writer writes again after one backoff"() {
        given:
        def calls = new AtomicInteger()
        writer = new SnapshotWriter(tempDir.resolve('snapshot.json'), {
            ->
            if (calls.incrementAndGet() == 1) {
                throw new Error('assembler error')
            }
            SnapshotWriterSpec.fixtureSnapshot()
        }, new SnapshotJsonMapper(), Duration.ofSeconds(30), VirtualTimeEquipment.create(), 0)

        when: 'the startup write throws'
        writer.start()

        then: 'the respawned writer wrote again, with nothing marking it dirty'
        new PollingConditions(timeout: 3).eventually { assert calls.get() == 2 }

        and: 'no lost tick was reported; the death was one ERROR, attributed to the snapshot writer'
        events(OperatorEvent.DAEMON_LOOP_TICK_FAILED).empty
        def died = events(OperatorEvent.DAEMON_LOOP_WORKER_DIED)
        died.size() == 1
        died[0].level == Level.ERROR
        died[0].MDCPropertyMap['component'] == DaemonComponent.SNAPSHOT.key()
    }

    // FR5, FR6, D3 (serve-observability "Interrupted writer does not spin"): an interrupt of the
    //     writer's thread with no stop requested is logged once and costs no extra write — after it,
    //     every write is a full timer period after the one before (or after the interrupt itself).
    def "a stray interrupt of the writer's thread leaves one write per timer period"() {
        given: 'a writer on a short timer that records when each write happened, and on which thread'
        def interval = Duration.ofMillis(200)
        def writtenAt = new CopyOnWriteArrayList<Long>()
        Thread worker = null
        writer = new SnapshotWriter(tempDir.resolve('snapshot.json'), {
            ->
            worker = Thread.currentThread()
            writtenAt << System.nanoTime()
            SnapshotWriterSpec.fixtureSnapshot()
        }, new SnapshotJsonMapper(), interval, VirtualTimeEquipment.create(), 0)
        writer.start()
        new PollingConditions(timeout: 3).eventually {
            assert writtenAt.size() == 1
        }

        when: 'the thread is interrupted while no stop is requested'
        long interruptedAt = System.nanoTime()
        worker.interrupt()
        new PollingConditions(timeout: 5).eventually {
            assert writtenAt.size() >= 4
        }

        then: 'the interrupt was absorbed as a stray, attributed to the writer'
        def strays = events(OperatorEvent.DAEMON_LOOP_STRAY_INTERRUPT)
        strays.size() == 1
        strays[0].level == Level.WARN
        strays[0].MDCPropertyMap['component'] == DaemonComponent.SNAPSHOT.key()

        and: 'no write came early: each is at least one timer period after its predecessor'
        def marks = [interruptedAt] + writtenAt.subList(1, 4)
        def minimumGap = TimeUnit.MILLISECONDS.toNanos((long) (interval.toMillis() * 0.8))
        (1..<marks.size()).every { i -> marks[i] - marks[i - 1] >= minimumGap }
    }
}
