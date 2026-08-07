package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.OutcomeCounts
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Verifies {@link LedgerAggregator} builds the dashboard history section's
 * view model (task 1.3 of add-dashboard-page): per-day {@code taskOutcome}
 * counts and window-total tokens-by-model, tolerating a torn last ledger
 * line (NFR-R2 of add-serve-observability) and a missing day's file (design
 * D5) without failing the window.
 *
 * FR6 of add-dashboard-page (design D5).
 */
class LedgerAggregatorSpec extends Specification {

    @TempDir
    Path tempDir

    def aggregator = new LedgerAggregator()

    private static final String INSTANCE = 'gnome-1'
    private static final LocalDate TODAY = LocalDate.parse('2026-08-06')

    def "aggregates outcome counts per day and sums tokens by model across the whole window"() {
        given:
        writeLedgerFile(TODAY.minusDays(1), [
            taskOutcomeLine('delivered', [claude: [input: 10, output: 5, cacheCreation: 0, cacheRead: 0]]),
            taskOutcomeLine('aborted', [claude: [input: 3, output: 1, cacheCreation: 0, cacheRead: 0]])
        ])
        writeLedgerFile(TODAY, [
            taskOutcomeLine('delivered', [claude: [input: 7, output: 2, cacheCreation: 0, cacheRead: 0]]),
            taskOutcomeLine('awaitingHuman', [gpt: [input: 4, output: 1, cacheCreation: 0, cacheRead: 0]]),
            taskOutcomeLine('revoked', [:])
        ])

        when:
        def view = aggregator.aggregate(tempDir, INSTANCE, TODAY, 2)

        then:
        view.perDay().size() == 2
        view.perDay()[0].date() == TODAY.minusDays(1)
        view.perDay()[0].counts() == new OutcomeCounts(1, 0, 1, 0)
        view.perDay()[1].date() == TODAY
        view.perDay()[1].counts() == new OutcomeCounts(1, 1, 0, 1)

        and: 'tokens are summed across both days, per model'
        view.tokensByModel()['claude'].input() == 20
        view.tokensByModel()['claude'].output() == 8
        view.tokensByModel()['gpt'].input() == 4
    }

    def "a torn last line in the newest file is skipped, not counted"() {
        given:
        writeLedgerFileRaw(TODAY,
                taskOutcomeLine('delivered', [:]) + '\n' + '{"version":1,"type":"taskOutcome","tas')

        when:
        def view = aggregator.aggregate(tempDir, INSTANCE, TODAY, 1)

        then:
        view.perDay().size() == 1
        view.perDay()[0].counts() == new OutcomeCounts(1, 0, 0, 0)
    }

    def "the window includes exactly the last windowDays days, not one more or fewer"() {
        given: 'a file older than the window, one at each edge, and one newer than today'
        writeLedgerFile(TODAY.minusDays(2), [
            taskOutcomeLine('delivered', [:])
        ])
        writeLedgerFile(TODAY.minusDays(1), [
            taskOutcomeLine('aborted', [:])
        ])
        writeLedgerFile(TODAY, [
            taskOutcomeLine('revoked', [:])
        ])

        when: 'a 2-day window ending at today'
        def view = aggregator.aggregate(tempDir, INSTANCE, TODAY, 2)

        then: 'only today and the day before are in range; the file two days back is excluded'
        view.perDay()*.date() == [TODAY.minusDays(1), TODAY]
    }

    def "tokensByModel sums cacheCreation and cacheRead across lines for the same model, not just input/output"() {
        given:
        writeLedgerFile(TODAY, [
            taskOutcomeLine('delivered', [claude: [input: 1, output: 1, cacheCreation: 100, cacheRead: 40]]),
            taskOutcomeLine('delivered', [claude: [input: 1, output: 1, cacheCreation: 7, cacheRead: 3]])
        ])

        when:
        def view = aggregator.aggregate(tempDir, INSTANCE, TODAY, 1)

        then:
        view.tokensByModel()['claude'].cacheCreation() == 107
        view.tokensByModel()['claude'].cacheRead() == 43
    }

    def "a missing ledger file within the window is skipped, not an error"() {
        given: 'only the most recent day has a file; the other 6 days of the default window are absent'
        writeLedgerFile(TODAY, [
            taskOutcomeLine('delivered', [:])
        ])

        when:
        def view = aggregator.aggregate(tempDir, INSTANCE, TODAY)

        then:
        view.perDay().size() == 1
        view.perDay()[0].date() == TODAY
    }

    def "non-taskOutcome lines are ignored"() {
        given:
        writeLedgerFile(TODAY, [
            '{"version":1,"type":"lifecycle","event":"started"}',
            taskOutcomeLine('delivered', [:])
        ])

        when:
        def view = aggregator.aggregate(tempDir, INSTANCE, TODAY, 1)

        then:
        view.perDay()[0].counts() == new OutcomeCounts(1, 0, 0, 0)
    }

    def "a taskOutcome line with an unknown or missing outcome is skipped, not a crash"() {
        given: 'a line written by a hypothetical newer factory version, plus one missing outcome, around a good line'
        writeLedgerFile(TODAY, [
            '{"version":1,"type":"taskOutcome","outcome":"escalated","tokensByModel":{}}',
            '{"version":1,"type":"taskOutcome","tokensByModel":{}}',
            taskOutcomeLine('delivered', [claude: [input: 9, output: 4, cacheCreation: 0, cacheRead: 0]])
        ])

        when: 'FR6: skip the unrecognized line and process the rest, without throwing (FR3, NFR-R1)'
        def view = aggregator.aggregate(tempDir, INSTANCE, TODAY, 1)

        then: 'only the recognized outcome is counted and its tokens summed; the window survives'
        view.perDay().size() == 1
        view.perDay()[0].counts() == new OutcomeCounts(1, 0, 0, 0)
        view.tokensByModel()['claude'].input() == 9
    }

    def "the default window is 7 days"() {
        expect:
        LedgerAggregator.DEFAULT_WINDOW_DAYS == 7
    }

    private void writeLedgerFile(LocalDate date, List<String> lines) {
        writeLedgerFileRaw(date, lines.join('\n') + '\n')
    }

    private void writeLedgerFileRaw(LocalDate date, String content) {
        Path file = ObservabilityPaths.ledgerFile(tempDir, INSTANCE, date)
        Files.createDirectories(file.parent)
        Files.writeString(file, content, StandardCharsets.UTF_8)
    }

    private static String taskOutcomeLine(String outcome, Map<String, Map<String, Long>> tokensByModel) {
        String tokens = tokensByModel.collect { model, usage ->
            "\"${model}\":{\"input\":${usage.input},\"output\":${usage.output}," +
            "\"cacheCreation\":${usage.cacheCreation ?: 0},\"cacheRead\":${usage.cacheRead ?: 0}}"
        }.join(',')
        return "{\"version\":1,\"type\":\"taskOutcome\",\"outcome\":\"${outcome}\",\"tokensByModel\":{${tokens}}}"
    }
}
