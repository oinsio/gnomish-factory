package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.FETCHED_AT
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.boardModel
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.render

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Verifies what the two board-fed blocks owe jointly (task 3.9 of
 * redesign-dashboard): each carries its own heading and its own board fetch
 * time, and a tracker outage degrades both, each stating it on its own
 * rather than one banner speaking for the page.
 *
 * FR2 of redesign-dashboard.
 */
class DashboardBoardBlockDegradationSpec extends Specification {

    def "each board-fed block carries its own heading and its own board timestamp"() {
        when:
        def html = render(new BoardSectionView(boardModel(), FETCHED_AT, null))

        then:
        html.contains('<h2 class="card__title">Waiting for a human</h2>')
        html.contains('<h2 class="card__title">In progress</h2>')

        and: 'both, not one, state when the board was fetched (FR2: each block carries its own)'
        html.count('board: <time datetime="2026-08-06T08:59:30Z"') == 2
    }

    // FR2: a tracker outage degrades BOTH board-fed blocks, each stating it on its own.
    def "a board that never fetched leaves both blocks unavailable, not just one"() {
        when:
        def html = render(new BoardSectionView(null, null, 'tracker unreachable: refused'))

        then:
        html.count('Board unavailable: tracker unreachable: refused') == 2
        html.count('board: never fetched') == 2
    }

    @Unroll
    def "both board-fed blocks degrade together: #description"() {
        when:
        def html = render(boardView)

        then:
        expectations.every { html.contains(it) }

        and:
        absent.every { !html.contains(it) }

        where:
        description | boardView | expectations | absent
        'a successful fetch shows its board time' | new BoardSectionView(boardModel(), FETCHED_AT, null) | [
            'board: <time datetime="2026-08-06T08:59:30Z"'
        ] | [
            'refresh failed',
            'Board unavailable'
        ]
        'a refresh failure keeps the cache, noted' | new BoardSectionView(boardModel(), FETCHED_AT, 'tracker: timeout') | [
            'board: <time datetime="2026-08-06T08:59:30Z"',
            'refresh failed, showing cache'
        ] | ['Board unavailable']
        'a board that never fetched is unavailable' | new BoardSectionView(null, null, 'tracker unreachable: refused') | [
            'board: never fetched',
            'Board unavailable: tracker unreachable: refused'
        ] | ['refresh failed']
    }
}
