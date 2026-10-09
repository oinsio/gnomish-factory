package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import java.io.IOException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code gnomish dashboard [--dir] [--out] [--watch]} (FR1, FR7 of add-dashboard-page; design D8):
 * renders the self-contained HTML dashboard page. Resolves the pipeline and {@code tracker:}
 * section from {@code --dir} exactly as {@link BoardCommand} does, mints a throwaway {@link
 * InstanceId} for the same reason {@link BoardCommand} does (design D8 — never written anywhere),
 * and hands the resulting {@link BoardSource} to one {@link DashboardWatch} per invocation — the one
 * dashboard assembly, which owns the output path ({@code --out}, or the page's file in the
 * instance's serve directory), the ready window, the board fetch and the render loop (design D10 of
 * supervise-daemon-loops-and-embed-dashboard). A one-shot run renders once through it; {@code
 * --watch} starts its supervised loop and joins it, ending with {@link DashboardDisabledException}
 * — exit status 1 — if the loop kept dying and was disabled (FR9).
 *
 * <p>Implements FR1, FR3, FR7, FR9, NFR-R2 of add-dashboard-page; FR3, FR10 of
 * add-project-registry; FR9, FR14 of supervise-daemon-loops-and-embed-dashboard.
 */
@Component
final class DashboardCommand {

    private final DashboardArgumentsParser argumentsParser = new DashboardArgumentsParser();
    private final TimeEquipment time;
    // The clone the configuration loader resolved from --dir (design D9 of add-project-registry).
    private final ProjectScope scope;
    private final FactoryProperties factoryProperties;
    private final TrackerWiring trackerWiring;

    DashboardCommand(
            TimeEquipment time, ProjectScope scope, FactoryProperties factoryProperties, TrackerWiring trackerWiring) {
        this.time = time;
        this.scope = scope;
        this.factoryProperties = factoryProperties;
        this.trackerWiring = trackerWiring;
    }

    /**
     * @param args the raw application arguments, including the leading {@code dashboard} token
     * @throws UsageException if the flags are malformed or the project has no {@code tracker:}
     *     section, or names an unregistered adapter type
     * @throws PipelineLoadFailedException if {@code .gnomish/} fails to load
     * @throws IOException if {@code .gnomish/} cannot be read (a genuine I/O fault), or the
     *     one-shot render cannot write its output file
     * @throws DashboardDisabledException if the {@code --watch} loop gave up (FR9 of
     *     supervise-daemon-loops-and-embed-dashboard)
     */
    void run(ApplicationArguments args) throws IOException {
        RegisteredClone clone = scope.registeredClone();
        DashboardArguments dashboardArguments = argumentsParser.parse(args, clone);
        ReadOnlyTrackerResolution resolution =
                trackerWiring.resolveReadOnly(dashboardArguments.dir(), scope.mintInstanceId());
        DashboardWatch watch = new DashboardWatch(
                clone.layout(),
                factoryProperties.instanceName(),
                dashboardArguments.out(),
                new BoardSource(resolution.tracker(), resolution.trackerConfig(), factoryProperties.tracker()),
                time);
        if (!dashboardArguments.watch()) {
            watch.renderOnce();
            return;
        }
        watch.start();
        if (watch.awaitEnd()) {
            throw new DashboardDisabledException();
        }
    }
}
