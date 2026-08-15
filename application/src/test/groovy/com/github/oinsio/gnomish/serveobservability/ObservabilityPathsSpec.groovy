package com.github.oinsio.gnomish.serveobservability

import java.nio.file.Path
import java.time.LocalDate
import spock.lang.Specification

/**
 * Verifies {@link ObservabilityPaths} against the deterministic directory/file
 * formula (design D2): {@code <home>/.gnomish/serve/<instance-name>/} keyed by
 * the configured instance *name*, stable across restarts regardless of the
 * full per-process instance id.
 *
 * FR9 of add-serve-observability.
 */
class ObservabilityPathsSpec extends Specification {

    def home = Path.of('/home/gnome')

    def "directory: resolves to <home>/.gnomish/serve/<instance-name>/"() {
        expect:
        ObservabilityPaths.directory(home, 'my-gnome') == home.resolve('.gnomish/serve/my-gnome')
    }

    def "directory: is stable across restarts regardless of the full per-process instance id"() {
        given: 'InstanceId = <name>-<suffix> (design D6 of add-tracker-port); only the name half is used'
        def suffixA = 'my-gnome-a1b2c3'
        def suffixB = 'my-gnome-d4e5f6'

        expect: 'both restarts of the same-named instance resolve to the same directory'
        ObservabilityPaths.directory(home, 'my-gnome') == ObservabilityPaths.directory(home, 'my-gnome')
        and: 'the full ids themselves are never used as the path key'
        ObservabilityPaths.directory(home, 'my-gnome') != home.resolve(".gnomish/serve/${suffixA}")
        ObservabilityPaths.directory(home, 'my-gnome') != home.resolve(".gnomish/serve/${suffixB}")
    }

    def "snapshotFile: resolves to snapshot.json inside the instance-name directory"() {
        expect:
        ObservabilityPaths.snapshotFile(home, 'my-gnome') ==
                home.resolve('.gnomish/serve/my-gnome/snapshot.json')
    }

    def "ledgerFile: resolves to ledger-<date>.jsonl inside the instance-name directory"() {
        expect:
        ObservabilityPaths.ledgerFile(home, 'my-gnome', LocalDate.of(2026, 8, 3)) ==
                home.resolve('.gnomish/serve/my-gnome/ledger-2026-08-03.jsonl')
    }

    def "ledgerFile: distinct dates produce distinct file names within the same directory"() {
        given:
        def day1 = ObservabilityPaths.ledgerFile(home, 'my-gnome', LocalDate.of(2026, 8, 3))
        def day2 = ObservabilityPaths.ledgerFile(home, 'my-gnome', LocalDate.of(2026, 8, 4))

        expect:
        day1 != day2
        day1.parent == day2.parent
    }
}
