package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.pipeline.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.board.BoardComposition;
import com.github.oinsio.gnomish.board.BoardModel;
import com.github.oinsio.gnomish.board.json.BoardJsonMapper;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code gnomish board [--dir] [--json] [--limit]} (FR1, NFR-S1 of add-board-command; design D8):
 * the read-only tracker board. Resolves the pipeline and {@code tracker:} section from {@code
 * --dir} exactly as {@link TakeCommand}/{@link ServeCommand} do (via {@link TakeCommandSupport}),
 * mints a throwaway {@link InstanceId} solely to satisfy {@link TrackerAdapterFactory#create}'s
 * constructor contract (design D8 — the id is never written anywhere), then calls only {@link
 * Tracker#listReady(int)} and {@link Tracker#listOpen()} — never a write method (NG3) — to build
 * one {@link BoardModel}.
 *
 * <p>Text output is rendered by {@link BoardTextRenderer} (task 4.1); the {@code --json} surface
 * is rendered by {@link BoardJsonMapper} (task 4.2) — both are projections of the same {@link
 * BoardModel} (UX4).
 *
 * <p>Implements FR1, NFR-S1 of add-board-command.
 */
@Component
final class BoardCommand {

    private final BoardArgumentsParser argumentsParser = new BoardArgumentsParser();
    private final BoardTextRenderer textRenderer = new BoardTextRenderer();
    private final BoardJsonMapper jsonMapper = new BoardJsonMapper();
    private final Clock clock;
    private final FactoryProperties factoryProperties;
    private final Map<String, TrackerAdapterFactory> trackerAdapterRegistry;
    private final Map<String, TrackerSubsectionValidator> trackerValidatorRegistry;

    BoardCommand(
            Clock javaTimeClock,
            FactoryProperties factoryProperties,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry) {
        this.clock = javaTimeClock;
        this.factoryProperties = factoryProperties;
        this.trackerAdapterRegistry = trackerAdapterRegistry;
        this.trackerValidatorRegistry = trackerValidatorRegistry;
    }

    /**
     * @param args the raw application arguments, including the leading {@code board} token
     * @throws UsageException if the flags are malformed or the project has no {@code tracker:}
     *     section, or names an unregistered adapter type
     * @throws PipelineLoadFailedException if {@code .gnomish/} fails to load
     * @throws IOException if {@code .gnomish/} cannot be read (a genuine I/O fault)
     */
    void run(ApplicationArguments args) throws IOException {
        BoardArguments boardArguments = argumentsParser.parse(args);
        PipelineDefinition definition = TakeCommandSupport.loadPipeline(boardArguments.dir(), trackerValidatorRegistry);
        TrackerConfig trackerConfig = TakeCommandSupport.requireTrackerConfig(definition);
        InstanceId instanceId = InstanceId.generate(factoryProperties.instanceName());
        Tracker tracker = TakeCommandSupport.resolveTracker(trackerConfig, trackerAdapterRegistry, instanceId.value());

        BoardModel model = BoardComposition.compose(
                tracker, trackerConfig, factoryProperties.tracker(), clock, boardArguments.limit());

        String output = boardArguments.json()
                ? jsonMapper.serialize(model, trackerConfig.wipLimit())
                : textRenderer.render(model);
        System.out.println(output);
    }
}
