package com.github.oinsio.gnomish.architecture

import static com.github.oinsio.gnomish.testsupport.ApplicationSourceScan.APP
import static com.github.oinsio.gnomish.testsupport.ApplicationSourceScan.spelling

import spock.lang.Specification

/**
 * FR9, FR14 and D10 of supervise-daemon-loops-and-embed-dashboard: the dashboard page has one
 * assembly, {@code DashboardWatch}, owning its output name and the board composition it renders.
 * FR10, NFR-S1 and D11 (single-owner row 4): the read-only tracker resolution stays out of
 * {@code serve}, reached only by the standalone {@code dashboard} and {@code board} commands. Each
 * allowlist is exact in both directions, so the scan is asserted to reach every owner.
 */
class DashboardOwnerBoundarySpec extends Specification {

    /** D10: the page's file name is spelled only by the dashboard assembly. */
    private static final String PAGE_NAME = '"dashboard.html"'
    private static final Set<String> PAGE_NAME_OWNERS = [
        APP + 'app/DashboardWatch.java'
    ] as Set

    /**
     * D10: the board is composed only by the dashboard assembly and by the standalone {@code board}
     * command. The declaration ({@code static BoardModel compose(}) carries no qualifier, so it is
     * not a match; a method reference is a call site too.
     */
    private static final List<String> BOARD_COMPOSE = [
        'BoardComposition.compose(',
        'BoardComposition::compose'
    ]
    private static final Set<String> BOARD_COMPOSE_OWNERS = [
        APP + 'app/DashboardWatch.java',
        APP + 'app/BoardCommand.java'
    ] as Set

    /** A static import would let a bare {@code compose(} call evade the qualified marker above. */
    private static final String BOARD_STATIC_IMPORT = 'import static com.github.oinsio.gnomish.board.BoardComposition.'

    /**
     * Row 4 (D11): the read-only tracker resolution, which reads a {@code SecretsProvider} from a
     * directory, is declared by the tracker wiring and called only by the two standalone read-only
     * commands; inside {@code serve} the board reader comes from {@code BoundTracker}. The bare name
     * matches the declaration, a qualified call and a method reference alike.
     */
    private static final List<String> READ_ONLY_RESOLUTION = [
        'resolveReadOnly(',
        '::resolveReadOnly'
    ]
    private static final Set<String> READ_ONLY_RESOLUTION_OWNERS = [
        APP + 'app/TrackerWiring.java',
        APP + 'app/DashboardCommand.java',
        APP + 'app/BoardCommand.java'
    ] as Set

    // FR9, FR14, D10: a second spelling of the page name is a second output path for one page.
    def "FR9, FR14, D10: the dashboard page name is spelled only by the dashboard assembly"() {
        expect: 'the scan reached the owner, it still spells the name, and no other file does'
        spelling([PAGE_NAME]) == PAGE_NAME_OWNERS
    }

    // FR9, FR14, D10: a third composition site is a second dashboard renderer beside the assembly.
    def "FR9, FR14, D10: the board is composed only by the dashboard assembly and the board command"() {
        expect: 'the scan reached both owners, each still composes, and no other file does'
        spelling(BOARD_COMPOSE) == BOARD_COMPOSE_OWNERS

        and: 'no file reaches the composition through a static import the qualified marker cannot see'
        spelling([BOARD_STATIC_IMPORT]).isEmpty()
    }

    // FR10, NFR-S1, D11: a serve-side call re-resolves the tracker from a directory beside the bound one.
    def "FR10, NFR-S1, D11: the read-only tracker resolution is reached only by the two standalone commands"() {
        expect: 'the scan reached the declaration and both callers, each still spells it, and no other file does'
        spelling(READ_ONLY_RESOLUTION) == READ_ONLY_RESOLUTION_OWNERS
    }
}
