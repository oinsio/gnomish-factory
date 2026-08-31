package com.github.oinsio.gnomish.board

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import spock.lang.Specification

/**
 * BoardLabels claim-freshness and truncation-marker formatting: the coarse "updated {age} ago"
 * string (and "freshness unknown" when the marker is missing) a Working row shows (FR4, design
 * D6), and the Ready-column truncation marker (FR3). Shared verbatim by the CLI board's text
 * renderer and the dashboard's HTML board section, so both surfaces render identical wording.
 *
 * <p>Implements FR3, FR4 of add-board-command; FR5 of add-dashboard-page.
 */
class BoardLabelsSpec extends Specification {

    private static final Instant NOW = Instant.parse('2026-08-05T12:00:00Z')

    // FR4: humanDuration renders each branch and boundary exactly, and folds a negative age to its
    // magnitude (the claim marker is display-only, never a staleness verdict — design D6).
    def "renders claim age as #expected for an age of #offsetSeconds seconds"() {
        given: 'a claim updated offsetSeconds before the observation instant (negative = in the future)'
        def updatedAt = NOW.minusSeconds(offsetSeconds)

        expect:
        BoardLabels.claimAge(updatedAt, NOW) == expected

        where:
        offsetSeconds || expected
        0 || 'updated 0s ago'
        30 || 'updated 30s ago'
        59 || 'updated 59s ago'
        60 || 'updated 1m ago'
        3540 || 'updated 59m ago'
        3600 || 'updated 1h ago'
        82800 || 'updated 23h ago'
        86400 || 'updated 1d ago'
        172800 || 'updated 2d ago'
        -30 || 'updated 30s ago'
    }

    // FR4: claimFreshness carries the age through when the marker is present and reports "freshness
    // unknown" when it is missing (claimVersion == null) — the single wording both surfaces share.
    def "claimFreshness renders the age when the marker is present"() {
        given:
        def claim = new ClaimVersion('m-1', NOW.minusSeconds(180), new ClaimEpoch(1))

        expect:
        BoardLabels.claimFreshness(claim, NOW) == 'updated 3m ago'
    }

    def "claimFreshness reports 'freshness unknown' when the marker is missing"() {
        expect:
        BoardLabels.claimFreshness(null, NOW) == 'freshness unknown'
    }

    // FR3: the truncation marker names the number of shown rows on a capped window, and is absent
    // otherwise. The count is readyRows.size() — identical to ReadySummary.queuedCount().
    def "truncationMarker names the shown-row count when the window is capped"() {
        given:
        def rows = [
            new ReadyRow(new TaskRef('r-1'), 't1', false, null),
            new ReadyRow(new TaskRef('r-2'), 't2', false, null)
        ]
        def model = new BoardModel(rows, [], [], ReadySummary.tally(rows), true, NOW)

        expect:
        BoardLabels.truncationMarker(model) == 'truncated: showing first 2 only'
    }

    def "truncationMarker is null when the window is not capped"() {
        given:
        def rows = [
            new ReadyRow(new TaskRef('r-1'), 't1', false, null)
        ]
        def model = new BoardModel(rows, [], [], ReadySummary.tally(rows), false, NOW)

        expect:
        BoardLabels.truncationMarker(model) == null
    }
}
