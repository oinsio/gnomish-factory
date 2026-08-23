package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DashboardHistoryFixtures.render
import static com.github.oinsio.gnomish.testsupport.DashboardHistoryFixtures.segmentWidths

import com.github.oinsio.gnomish.serveobservability.LedgerTokenUsage
import com.github.oinsio.gnomish.serveobservability.OutcomeCounts
import java.time.LocalDate
import spock.lang.Specification

/**
 * Verifies the per-model token block (tasks 3.6, 3.8 of redesign-dashboard):
 * bars captioned with the cache share, compact counts carrying their exact
 * values, and never the status palette on spend.
 *
 * FR7, FR9, UX3 of redesign-dashboard.
 */
class DashboardTokensBlockSpec extends Specification {

    // FR7: the token bar carries the same promise as the outcome bar.
    def "a marginal token class stays visible and the model bar fills exactly"() {
        given: 'one input token against 299 read from cache'
        def history = new LedgerHistoryView([], ['sonnet': new LedgerTokenUsage(1L, 0L, 0L, 299L)])

        when:
        def html = render(history)

        then:
        segmentWidths(html).sum() == 100
        html.contains('style="width:1%;background:var(--seg-in)"')
        html.contains('style="width:99%;background:var(--seg-cache-read)"')
    }

    // FR7, FR9: the cache share leads the caption, and every count carries its exact value.
    def "a model's bar is captioned with its integer cache share and compact in/out counts"() {
        given: 'input 3.7K, output 28.8K, cacheCreation 25.6K, cacheRead 4.7644M'
        def history = new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(1, 0, 0, 0))
                ],
                [claude: new LedgerTokenUsage(3700, 28_800, 25_600, 4_764_400)])

        when:
        def html = render(history)

        then: 'cacheRead(4764400) + cacheCreation(25600) of the 4822500 total is 99%'
        html.contains('<div class="bar-note">99% from cache · in 3.7K · out 28.8K</div>')

        and: 'the model total is compact with the exact value on hover (FR9)'
        html.contains('title="4822500">4.82M</span>')

        and: 'the bar names every segment for a reader who cannot see it'
        html.contains('role="img" aria-label="4764400 cache read, 25600 cache creation, '
                + '3700 input, 28800 output"')
    }

    // FR7: spend is never coloured with the status palette — a large number is not an alarm.
    def "every bar segment comes from the spend palette"() {
        given: 'a mix where all four segments clear one percent and so all four are drawn'
        def history = new LedgerHistoryView([], [claude: new LedgerTokenUsage(100_000, 200_000, 300_000, 400_000)])

        when:
        def html = render(history)

        then:
        html.contains('style="width:40%;background:var(--seg-cache-read)"')
        html.contains('style="width:30%;background:var(--seg-cache-write)"')
        html.contains('style="width:10%;background:var(--seg-in)"')
        html.contains('style="width:20%;background:var(--seg-out)"')

        and:
        !html.contains('background:var(--bad-fg)')
        !html.contains('background:var(--warn-dot)')
    }

    // FR7: zero cache says so — a 0% would read as a cache that exists and is failing.
    def "a model with no cache traffic says the cache is not in use"() {
        given:
        def history = new LedgerHistoryView([], [claude: new LedgerTokenUsage(10, 5, 0, 0)])

        when:
        def html = render(history)

        then:
        html.contains('cache not in use · in 10 · out 5')
        !html.contains('0% from cache')
    }

    // UX3: a refresh must not reshuffle rows under the reader's eye.
    def "models render in id order regardless of the map's own order"() {
        given:
        def history = new LedgerHistoryView([], [
            zeta: new LedgerTokenUsage(1, 1, 0, 0),
            alpha: new LedgerTokenUsage(1, 1, 0, 0)
        ])

        when:
        def html = render(history)

        then: 'both models are present — an absent one would make the index comparison vacuous'
        html.contains('>alpha<')
        html.contains('>zeta<')
        html.indexOf('>alpha<') <html.indexOf('>zeta<')
    }

    // The heading's period comes from the outcome days; token usage recorded in a
    // window with no finished task renders the grand total alone, with no period.
    def "token usage with no finished tasks renders the grand total without a period"() {
        given:
        def history = new LedgerHistoryView([], [claude: new LedgerTokenUsage(10, 5, 0, 0)])

        when:
        def html = render(history)

        then:
        html.contains('<h2 class="card__title">Tokens</h2>' +
                '<span class="card__meta"><span class="num" title="15">15</span></span></div>')
    }

    def "the heading carries the grand total across models and its empty state when there is none"() {
        given:
        def history = new LedgerHistoryView([], [
            a: new LedgerTokenUsage(1000, 0, 0, 0),
            b: new LedgerTokenUsage(500, 0, 0, 0)
        ])

        expect:
        render(history).contains('<span class="num" title="1500">1.5K</span>')

        and: 'with no models there is no total to state, so the heading carries no meta slot at all'
        def empty = render(new LedgerHistoryView([], [:]))
        empty.contains('No token usage recorded yet')
        empty.contains('<h2 class="card__title">Tokens</h2></div>')
    }
}
