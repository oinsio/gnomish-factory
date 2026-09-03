package com.github.oinsio.gnomish.dashboard

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapperSpec
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

/**
 * Verifies {@link SnapshotReader} builds the dashboard daemon section's
 * view model (task 1.1 of add-dashboard-page): a missing/unreadable file
 * degrades to {@link DaemonSnapshotView.Absent} without throwing (FR3), and
 * staleness — computed from the snapshot's own {@code writtenAt} +
 * {@code intervalSeconds} with {@code k = 3} — is combined with lifecycle
 * state to distinguish a dead daemon from a clean stop (FR4, design D3).
 *
 * FR3, FR4 of add-dashboard-page.
 */
class SnapshotReaderSpec extends Specification {

    @TempDir
    Path tempDir

    def mapper = new SnapshotJsonMapper()
    def reader = new SnapshotReader()

    private static final Instant WRITTEN_AT = Instant.parse('2026-08-02T09:00:00Z')
    // intervalSeconds = 30, k = 3 -> staleness threshold is 90s.
    private static final Instant FRESH_NOW = WRITTEN_AT.plusSeconds(60)
    private static final Instant STALE_NOW = WRITTEN_AT.plusSeconds(91)

    // FR5 of harden-logging-observability: missing and malformed render identically, which is
    // exactly why they must not read alike in the log — a daemon that IS writing a snapshot this
    // reader cannot parse is a defect; a snapshot that was never written usually is not.
    def "a missing snapshot file degrades to Absent without throwing, at DEBUG"() {
        given:
        def missing = tempDir.resolve('snapshot.json')
        def logs = LogCaptureSupport.attach(SnapshotReader, Level.DEBUG)

        when:
        def view = reader.read(missing, FRESH_NOW)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        view == new DaemonSnapshotView.Absent()

        and:
        events.size() == 1
        events[0].level == Level.DEBUG
        events[0].formattedMessage.contains('no daemon snapshot at')
    }

    def "malformed JSON degrades to Absent without throwing, at WARN"() {
        given:
        def file = writeText(tempDir, '{not valid json')
        def logs = LogCaptureSupport.attach(SnapshotReader, Level.DEBUG)

        when:
        def view = reader.read(file, FRESH_NOW)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        view == new DaemonSnapshotView.Absent()

        and:
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.DAEMON_SNAPSHOT_UNREADABLE.head())
        warnings[0].formattedMessage.contains('present but unreadable')

        and: 'the parse failure keeps its stack'
        warnings[0].throwableProxy != null
    }

    def "a fresh snapshot within k x intervalSeconds renders Fresh regardless of lifecycle"() {
        given:
        def file = writeSnapshot(snapshotWithLifecycle(new LifecycleState.Running()))

        when:
        def view = reader.read(file, FRESH_NOW)

        then:
        view instanceof DaemonSnapshotView.Fresh
        (view as DaemonSnapshotView.Fresh).snapshot().lifecycle() == new LifecycleState.Running()
    }

    @Unroll
    def "a stale snapshot with lifecycle #lifecycle renders DeadDaemon"() {
        given:
        def file = writeSnapshot(snapshotWithLifecycle(lifecycle))

        when:
        def view = reader.read(file, STALE_NOW)

        then:
        view instanceof DaemonSnapshotView.DeadDaemon
        (view as DaemonSnapshotView.DeadDaemon).snapshot().lifecycle() == lifecycle

        where:
        lifecycle << [
            new LifecycleState.Running(),
            new LifecycleState.Draining(),
            new LifecycleState.Stopping()
        ]
    }

    def "a snapshot exactly at k x intervalSeconds is still Fresh, not stale"() {
        given: 'intervalSeconds=30, k=3 -> threshold is exactly 90s'
        def file = writeSnapshot(snapshotWithLifecycle(new LifecycleState.Running()))
        def atThreshold = WRITTEN_AT.plusSeconds(90)

        expect:
        reader.read(file, atThreshold) instanceof DaemonSnapshotView.Fresh
    }

    def "a stale snapshot last in Stopped renders StoppedStale, not DeadDaemon"() {
        given:
        def stopped = new LifecycleState.Stopped('drainComplete')
        def file = writeSnapshot(snapshotWithLifecycle(stopped))

        when:
        def view = reader.read(file, STALE_NOW)

        then:
        view instanceof DaemonSnapshotView.StoppedStale
        (view as DaemonSnapshotView.StoppedStale).snapshot().lifecycle() == stopped
    }

    private Path writeSnapshot(Snapshot snapshot) {
        writeText(tempDir, mapper.serialize(snapshot))
    }

    private static Path writeText(Path dir, String text) {
        def file = dir.resolve("snapshot-${UUID.randomUUID()}.json")
        Files.writeString(file, text, StandardCharsets.UTF_8)
        return file
    }

    private static Snapshot snapshotWithLifecycle(LifecycleState lifecycle) {
        def snapshot = SnapshotJsonMapperSpec.referenceSnapshot()
        return new Snapshot(
                snapshot.version(), snapshot.writtenAt(), snapshot.intervalSeconds(), snapshot.instance(),
                lifecycle, snapshot.feed(), snapshot.slots(), snapshot.vitals(), snapshot.tracker())
    }
}
