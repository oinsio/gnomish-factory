package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import java.util.Optional;

/**
 * The page inside {@code serve} (design D11, D12 of supervise-daemon-loops-and-embed-dashboard):
 * decides from the effective switch whether the daemon renders a dashboard at all, and when it
 * does, builds the one {@link DashboardWatch} (D10) over a {@link BoardSource} whose tracker comes
 * from {@link BoardReaders#boardReader} — the configuration and adapter factory {@code serve} bound
 * from origin's default branch, under a reader id minted per invocation, never the daemon's
 * claiming identity — and whose configuration is that same bound one.
 *
 * <p>It holds {@link BoardReaders}, never the whole {@link TrackerWiring} nor a secrets seam
 * (NFR-S1: use of the credentials is granted, possession is not), and it takes no {@code Path dir},
 * so no second configuration can be read from the checkout. Split from {@link
 * ServeRuntimeAssembly} because this is a decision — the runtime assembly carries none — and it
 * holds the decision's own equipment as fields: the project scope the reader id is minted from and
 * whose layout names the page's serve directory, and the factory properties' instance name and
 * tracker settings the board is composed with.
 *
 * <p>Implements FR9, FR10, NFR-S1, NFR-O2 of supervise-daemon-loops-and-embed-dashboard.
 */
final class ServeDashboard {

    private final BoardReaders boardReaders;
    private final ProjectScope scope;
    private final FactoryProperties factoryProperties;
    private final TimeEquipment time;

    /**
     * @param boardReaders builds the read-only board client from the bound configuration (D11)
     * @param scope the registered clone, whose layout holds the serve directory, and the minter of
     *     the reader's own instance id
     * @param factoryProperties supplies the instance name and the tracker settings of the board
     * @param time the daemon's one time equipment, which the page's render loop runs on
     */
    ServeDashboard(
            BoardReaders boardReaders, ProjectScope scope, FactoryProperties factoryProperties, TimeEquipment time) {
        this.boardReaders = boardReaders;
        this.scope = scope;
        this.factoryProperties = factoryProperties;
        this.time = time;
    }

    /**
     * Builds the daemon's dashboard, not yet started, or nothing when the effective switch is off —
     * in which case no reader is built, so no tracker client exists for a page nobody asked for.
     *
     * @param serveArguments the parsed invocation: its effective switch and its page path override
     * @param bound what {@code serve} bound from origin's default branch; never null
     * @return the page's watch, or empty when the dashboard is off
     */
    Optional<DashboardWatch> forRun(ServeArguments serveArguments, BoundTracker bound) {
        if (!serveArguments.dashboard()) {
            return Optional.empty();
        }
        BoardSource source = new BoardSource(
                boardReaders.boardReader(bound, scope.mintInstanceId()),
                bound.trackerConfig(),
                factoryProperties.tracker());
        return Optional.of(new DashboardWatch(
                scope.registeredClone().layout(),
                factoryProperties.instanceName(),
                serveArguments.dashboardOut(),
                source,
                time));
    }
}
