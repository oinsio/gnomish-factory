package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.pipeline.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.board.BoardComposition;
import com.github.oinsio.gnomish.board.BoardModel;
import com.github.oinsio.gnomish.dashboard.BoardSectionView;
import com.github.oinsio.gnomish.dashboard.DashboardBoardCache;
import com.github.oinsio.gnomish.dashboard.DashboardRenderCycle;
import com.github.oinsio.gnomish.dashboard.DashboardWatchLoop;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths;
import com.github.oinsio.gnomish.serveobservability.writer.AtomicFileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code gnomish dashboard [--dir] [--out] [--watch]} (FR1, FR7 of add-dashboard-page; design D8):
 * renders the self-contained HTML dashboard page. Resolves the pipeline and {@code tracker:}
 * section from {@code --dir} exactly as {@link BoardCommand} does, mints a throwaway {@link
 * InstanceId} for the same reason {@link BoardCommand} does (design D8 — never written anywhere),
 * and hands the board's fetch call — not its result — to the render path: a one-shot render fetches
 * it once via a fresh {@link DashboardBoardCache} (task 4.3); {@code --watch} hands it to {@link
 * DashboardWatchLoop}, which re-fetches only on its own slower cadence (task 4.4, FR9).
 *
 * <p>The default output path is {@code dashboard.html} in the instance's observability directory
 * (design D8, {@link ObservabilityPaths#directory}); {@code --out} overrides it. Every write goes
 * through {@link AtomicFileWriter} (task 4.2, NFR-R2) via {@link DashboardRenderCycle}'s callers.
 *
 * <p>Implements FR1, FR3, FR7, FR9, NFR-R2 of add-dashboard-page.
 */
@Component
final class DashboardCommand {

    static final String DEFAULT_FILE_NAME = "dashboard.html";
    private static final int BOARD_READY_LIMIT = 50;

    private final DashboardArgumentsParser argumentsParser = new DashboardArgumentsParser();
    private final DashboardRenderCycle renderCycle = new DashboardRenderCycle();
    private final Clock clock;
    private final Sleeper sleeper;
    private final Path homeDir;
    private final FactoryProperties factoryProperties;
    private final Map<String, TrackerAdapterFactory> trackerAdapterRegistry;
    private final Map<String, TrackerSubsectionValidator> trackerValidatorRegistry;

    DashboardCommand(
            Clock javaTimeClock,
            Sleeper sleeper,
            Path homeDir,
            FactoryProperties factoryProperties,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry) {
        this.clock = javaTimeClock;
        this.sleeper = sleeper;
        this.homeDir = homeDir;
        this.factoryProperties = factoryProperties;
        this.trackerAdapterRegistry = trackerAdapterRegistry;
        this.trackerValidatorRegistry = trackerValidatorRegistry;
    }

    /**
     * @param args the raw application arguments, including the leading {@code dashboard} token
     * @throws UsageException if the flags are malformed or the project has no {@code tracker:}
     *     section, or names an unregistered adapter type
     * @throws PipelineLoadFailedException if {@code .gnomish/} fails to load
     * @throws IOException if {@code .gnomish/} cannot be read (a genuine I/O fault), or the
     *     one-shot render cannot write its output file
     */
    void run(ApplicationArguments args) throws IOException {
        DashboardArguments dashboardArguments = argumentsParser.parse(args);
        PipelineDefinition definition =
                TakeCommandSupport.loadPipeline(dashboardArguments.dir(), trackerValidatorRegistry);
        TrackerConfig trackerConfig = TakeCommandSupport.requireTrackerConfig(definition);
        InstanceId instanceId = InstanceId.generate(factoryProperties.instanceName());
        Tracker tracker = TakeCommandSupport.resolveTracker(trackerConfig, trackerAdapterRegistry, instanceId.value());
        String instanceName = factoryProperties.instanceName();
        Path outputFile = dashboardArguments.out() != null
                ? dashboardArguments.out()
                : ObservabilityPaths.directory(homeDir, instanceName).resolve(DEFAULT_FILE_NAME);
        Supplier<BoardModel> boardFetch = () ->
                BoardComposition.compose(tracker, trackerConfig, factoryProperties.tracker(), clock, BOARD_READY_LIMIT);

        if (dashboardArguments.watch()) {
            new DashboardWatchLoop(renderCycle, sleeper, clock).run(homeDir, instanceName, outputFile, boardFetch);
            return;
        }
        renderOnce(instanceName, outputFile, boardFetch);
    }

    private void renderOnce(String instanceName, Path outputFile, Supplier<BoardModel> boardFetch) throws IOException {
        Instant now = clock.instant();
        BoardSectionView boardView = new DashboardBoardCache().refresh(boardFetch, now);
        String html = renderCycle.render(homeDir, instanceName, boardView, now, null);
        AtomicFileWriter.write(outputFile, html);
    }
}
