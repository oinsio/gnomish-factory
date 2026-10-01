package com.github.oinsio.gnomish.serveobservability

import java.nio.file.Path
import java.time.LocalDate
import spock.lang.Specification

/**
 * Verifies {@link ObservabilityPaths}: the file names inside an instance's serve directory. The
 * directory itself is the registered project's {@code serve/<instance>} (design D1 of
 * add-project-registry), computed by the caller; this class only names the files in it.
 *
 * FR9 of add-serve-observability; FR10 of add-project-registry.
 */
class ObservabilityPathsSpec extends Specification {

    def serveDir = Path.of('/home/gnome/.gnomish/projects/widgets/serve/default')

    def "snapshotFile: resolves to snapshot.json inside the serve directory"() {
        expect:
        ObservabilityPaths.snapshotFile(serveDir) == serveDir.resolve('snapshot.json')
    }

    def "ledgerFile: resolves to ledger-<date>.jsonl inside the serve directory"() {
        expect:
        ObservabilityPaths.ledgerFile(serveDir, LocalDate.of(2026, 8, 3)) ==
                serveDir.resolve('ledger-2026-08-03.jsonl')
    }

    def "ledgerFile: distinct dates produce distinct file names within the same directory"() {
        given:
        def day1 = ObservabilityPaths.ledgerFile(serveDir, LocalDate.of(2026, 8, 3))
        def day2 = ObservabilityPaths.ledgerFile(serveDir, LocalDate.of(2026, 8, 4))

        expect:
        day1 != day2
        day1.parent == day2.parent
    }
}
