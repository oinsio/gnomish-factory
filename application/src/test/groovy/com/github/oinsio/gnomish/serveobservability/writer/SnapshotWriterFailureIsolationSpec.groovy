package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link SnapshotWriter#tick}: every failure between obtaining the snapshot and landing it on
 * disk is isolated (task 3.6, NFR-R1 of add-serve-observability) — a supplier failure, a real
 * serialization failure, and a write failure must all be logged and swallowed rather than
 * propagated, and the retention sweep must still run afterward on every one of those paths so a
 * write failure never suppresses it.
 *
 * <p>Implements NFR-R1 of add-serve-observability.
 */
class SnapshotWriterFailureIsolationSpec extends Specification {

    @TempDir
    Path tempDir

    def mapper = new SnapshotJsonMapper()
    def clock = Clock.fixed(Instant.parse('2026-08-03T10:00:00Z'), ZoneOffset.UTC)

    def "a supplier failure (unchecked, not an IOException) is swallowed and the retention sweep still runs"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def staleLedger = tempDir.resolve('ledger-2020-01-01.jsonl')
        Files.writeString(staleLedger, 'stale')
        def writer = new SnapshotWriter(target, {
            -> throw new NullPointerException('assembler bug')
        }, mapper, Duration.ofSeconds(30), clock, 1)

        when:
        writer.tick()

        then:
        noExceptionThrown()
        !Files.exists(target)
        !Files.exists(staleLedger)
    }

    // A malformed Snapshot (a required section null) makes the real SnapshotJsonMapper.serialize
    // fail with a genuine unchecked NullPointerException deep inside its DTO mapping — proving
    // tick() isolates a real serialization failure, not just a stubbed one.
    def "a real serialization failure from a malformed snapshot is swallowed and the retention sweep still runs"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def staleLedger = tempDir.resolve('ledger-2020-01-01.jsonl')
        Files.writeString(staleLedger, 'stale')
        def malformed = SnapshotWriterSpec.fixtureSnapshot().withSelfDescription(Instant.parse('2026-08-03T09:59:00Z'), 30L)
        // instance() is required by SnapshotJsonMapper#toInstance; a fresh record built with a
        // null instance triggers a real, unstubbed NPE deep inside toDto().
        def poisoned = new com.github.oinsio.gnomish.serveobservability.Snapshot(
                malformed.version(), malformed.writtenAt(), malformed.intervalSeconds(), null,
                malformed.lifecycle(), malformed.feed(), malformed.slots(), malformed.vitals(), malformed.tracker())
        def writer = new SnapshotWriter(target, {
            -> poisoned
        }, mapper, Duration.ofSeconds(30), clock, 1)

        when:
        writer.tick()

        then:
        noExceptionThrown()
        !Files.exists(target)
        !Files.exists(staleLedger)
    }

    // NFR-R1: a checked IOException from AtomicFileWriter must not crash tick() nor leave the
    // target half-written; the write failure and the retention sweep (proven above to still run
    // after a write failure) are independent failure domains.
    def "an atomic-write IOException is swallowed rather than propagated"() {
        given:
        def blockingFile = tempDir.resolve('not-a-directory')
        Files.writeString(blockingFile, 'not a directory')
        def target = blockingFile.resolve('snapshot.json')
        def writer = new SnapshotWriter(target, {
            -> SnapshotWriterSpec.fixtureSnapshot()
        }, mapper, Duration.ofSeconds(30), clock, 1)

        when:
        writer.tick()

        then:
        noExceptionThrown()
        !Files.exists(target)
    }

    def "repeated failing ticks never crash and each one still attempts the write again"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def calls = 0
        def writer = new SnapshotWriter(target, {
            -> calls++; throw new RuntimeException('still broken')
        }, mapper, Duration.ofSeconds(30), clock, 0)

        when:
        writer.tick()
        writer.tick()
        writer.tick()

        then:
        noExceptionThrown()
        calls == 3
    }
}
