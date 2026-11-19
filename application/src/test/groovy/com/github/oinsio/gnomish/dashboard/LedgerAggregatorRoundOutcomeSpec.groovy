package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.OutcomeCounts
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import com.github.oinsio.gnomish.serveobservability.json.RoundOutcomeLineMapperSpec
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-O2, UX3 of define-executor-contract: the dashboard's day view sums {@code taskOutcome}
 * snapshots only, so a day's {@code roundOutcome} lines — written by the real ledger mapper — leave
 * its counts and tokens exactly as they are without them (the reader end of the declared pair
 * {@code LedgerJsonMapper} ↔ {@code LedgerAggregator}); so do an executor check's lines, role {@code
 * verdict}, with their {@code check} index and {@code resumedKey} (NFR-O1, UX3 of
 * migrate-verify-to-executor-contract).
 */
class LedgerAggregatorRoundOutcomeSpec extends Specification {

    static final LocalDate TODAY = LocalDate.parse('2026-08-06')
    static final String TASK_LINE = '{"version":1,"type":"taskOutcome","outcome":"delivered",' +
    '"tokensByModel":{"claude":{"input":7,"output":2,"cacheCreation":0,"cacheRead":0}}}'

    @TempDir
    Path withRounds

    @TempDir
    Path withoutRounds

    def "roundOutcome lines change neither the day's counts nor its tokens"() {
        given:
        def round = new LedgerJsonMapper().serialize(RoundOutcomeLineMapperSpec.limitRoundLine())
        write(withRounds, [round, TASK_LINE, round])
        write(withoutRounds, [TASK_LINE])
        def aggregator = new LedgerAggregator()

        when:
        def view = aggregator.aggregate(withRounds, TODAY, 1)

        then:
        view == aggregator.aggregate(withoutRounds, TODAY, 1)
        view.perDay()[0].counts() == new OutcomeCounts(1, 0, 0, 0)
        view.tokensByModel()['claude'].input() == 7
    }

    // NFR-O1, UX3 of migrate-verify-to-executor-contract: an executor check's line, resumed key included
    def "an executor check's roundOutcome line, a resumed one included, changes neither the day's counts nor its tokens"() {
        given:
        def check = new LedgerJsonMapper().serialize(RoundOutcomeLineMapperSpec.checkLine(UntrustedText.branchDocument('k-1')))
        write(withRounds, [check, TASK_LINE])
        write(withoutRounds, [TASK_LINE])
        def aggregator = new LedgerAggregator()

        expect:
        aggregator.aggregate(withRounds, TODAY, 1) == aggregator.aggregate(withoutRounds, TODAY, 1)
    }

    private static void write(Path serveDir, List<String> lines) {
        Path file = ObservabilityPaths.ledgerFile(serveDir, TODAY)
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.join('\n') + '\n', StandardCharsets.UTF_8)
    }
}
