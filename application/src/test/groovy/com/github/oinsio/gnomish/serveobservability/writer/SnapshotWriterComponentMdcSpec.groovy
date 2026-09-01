package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import com.github.oinsio.gnomish.status.DaemonComponent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * The wiring half of {@link DaemonComponent} (FR8, design D10 of harden-logging-observability): a
 * daemon that really starts a thread really runs its loop inside the component frame, so the lines
 * it emits from that thread name their speaker.
 *
 * <p>The snapshot writer is the subject because it is the one daemon a spec can start and stop on
 * demand — the janitor, sweep tick and heartbeat loops run for the process's whole life. It is
 * driven into its own failure-isolation path (a supplier that always throws) purely to make it
 * speak from inside the loop; what that line <em>says</em> is
 * {@code SnapshotWriterFailureIsolationSpec}'s subject, not this one's. The captured logger is the
 * write cycle's, since that is where the isolated failure is reported.
 *
 * <p>Implements FR8 of harden-logging-observability.
 */
// Bound the feature: a broken framing mutant must surface as a red assertion within budget rather
// than a PIT TIMED_OUT.
@Timeout(10)
class SnapshotWriterComponentMdcSpec extends Specification {

    @TempDir
    Path tempDir

    def "a line emitted from the started writer's own thread carries component=snapshot"() {
        given: 'a writer whose supplier always fails, so its loop logs from inside the frame'
        def capture = LogCaptureSupport.attach(SnapshotWriteCycle)
        def writer = new SnapshotWriter(
                tempDir.resolve('snapshot.json'),
                { -> throw new IllegalStateException('assembler bug') },
                new SnapshotJsonMapper(),
                Duration.ofSeconds(30),
                Clock.fixed(Instant.parse('2026-08-31T10:00:00Z'), ZoneOffset.UTC),
                0)

        when:
        writer.start()

        then:
        new PollingConditions(timeout: 5).eventually {
            assert capture.list.any {
                it.MDCPropertyMap['component'] == DaemonComponent.SNAPSHOT.key()
            }
        }

        cleanup:
        writer.stop()
        capture.detach()
    }
}
