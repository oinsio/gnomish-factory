package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.serveobservability.LedgerTokenUsage
import com.github.oinsio.gnomish.serveobservability.OutcomeCounts
import java.time.LocalDate
import spock.lang.Specification

/**
 * Dedicated verification of {@link DashboardHistorySectionRenderer}'s
 * magnitude bars: each row's inline bar width scales against the
 * largest-volume row in its table, so relative volume reads at a glance.
 * The numbers are chosen so every arithmetic and boundary mutation in the
 * total/max/ratio math changes an asserted pixel width — a per-day outcome
 * total of 15 against a max of 100 renders an 18px bar, the max row 120px.
 *
 * <p>Implements FR3, FR6, NFR-O1 of add-dashboard-page (design D6).
 */
class DashboardHistorySectionRendererSpec extends Specification {

    def renderer = new DashboardHistorySectionRenderer()

    // FR6: an outcome row's volume bar scales to the max-total row (100 -> 120px, 15 -> 18px).
    def "outcome bars scale each day's total against the largest-total day"() {
        given: 'two days with distinct component counts, the second the clear volume max'
        def history = new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(1, 2, 4, 8)),
                    new DayOutcomeCounts(LocalDate.parse('2026-08-06'), new OutcomeCounts(10, 20, 30, 40)),
                ],
                [:])
        def out = new StringBuilder()

        when:
        renderer.append(out, history)
        def html = out.toString()

        then: 'both days render their raw component counts in order'
        html.contains('<td>2026-08-05</td><td>1</td><td>2</td><td>4</td><td>8</td>')
        html.contains('<td>2026-08-06</td><td>10</td><td>20</td><td>30</td><td>40</td>')

        and: 'day2 total 100 fills the bar and day1 total 15 renders 15/100*120 = 18px'
        // Kills: dayTotal return-0 and add->sub (every mutant zeroes/skews these widths),
        // appendBarCell div<->mul and the == 0 negate (all collapse the widths),
        // and appendOutcomeTable VoidMethodCall (removing the call drops the bar cell entirely).
        html.contains('<span class="bar" style="width:120px"></span>')
        html.contains('<span class="bar" style="width:18px"></span>')

        and: 'the section reports the range its days cover'
        html.contains('covers 2026-08-05 to 2026-08-06')
    }

    // FR6: a token row's volume bar scales input+output against the max-volume model.
    def "token bars scale each model's input+output against the largest-volume model"() {
        given: 'two models with distinct input!=output volumes, the second the clear max'
        LinkedHashMap<String, LedgerTokenUsage> tokens = [:]
        tokens.put('m1', new LedgerTokenUsage(3, 5, 7, 9))
        tokens.put('m2', new LedgerTokenUsage(60, 40, 0, 0))
        def history = new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(1, 0, 0, 0))
                ],
                tokens)
        def out = new StringBuilder()

        when:
        renderer.append(out, history)
        def html = out.toString()

        then: 'each model renders its four raw token counts in order'
        html.contains('<td>m1</td><td>3</td><td>5</td><td>7</td><td>9</td>')
        html.contains('<td>m2</td><td>60</td><td>40</td><td>0</td><td>0</td>')

        and: 'm2 volume 100 fills the bar and m1 volume 8 renders 8/100*120 = 10px'
        // Kills: appendTokenTable max input+output add->sub (L70) and the per-row
        // input+output add->sub (L87), which skew these widths, plus the L87
        // VoidMethodCall (removing the call drops m1's 10px bar). 10px is unique to m1.
        html.contains('<span class="bar" style="width:120px"></span>')
        html.contains('<span class="bar" style="width:10px"></span>')
    }

    def "an empty ledger window renders a plain no-data section, never an error"() {
        given:
        def out = new StringBuilder()

        when:
        renderer.append(out, new LedgerHistoryView([], [:]))
        def html = out.toString()

        then:
        html.contains('<p>no history data</p>')
        !html.toLowerCase().contains('error')
    }
}
