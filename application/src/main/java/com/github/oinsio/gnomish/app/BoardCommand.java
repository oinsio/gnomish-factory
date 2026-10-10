package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.board.BoardComposition;
import com.github.oinsio.gnomish.board.BoardModel;
import com.github.oinsio.gnomish.board.json.BoardJsonMapper;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.io.IOException;
import java.time.InstantSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code gnomish board [--dir] [--json] [--limit]} (FR1, NFR-S1 of add-board-command; design D8):
 * the read-only tracker board. Resolves the pipeline and {@code tracker:} section from {@code
 * --dir} exactly as {@link TakeCommand}/{@link ServeCommand} do (via {@link TakeCommandSupport}),
 * the directory being the registered clone the configuration loader resolved ({@link ProjectScope},
 * FR3 of add-project-registry), mints a throwaway {@link InstanceId} solely to satisfy {@link
 * TrackerAdapterFactory#create}'s constructor contract (design D8 — the id is never written
 * anywhere; it names the project all the same, FR10 of add-project-registry), then calls only {@link
 * Tracker#listReady(int)} and {@link Tracker#listOpen()} — never a write method (NG3) — to build
 * one {@link BoardModel}.
 *
 * <p>Text output is rendered by {@link BoardTextRenderer} (task 4.1); the {@code --json} surface
 * is rendered by {@link BoardJsonMapper} (task 4.2) — both are projections of the same {@link
 * BoardModel} (UX4).
 *
 * <p>Implements FR1, NFR-S1 of add-board-command; FR5 of harden-untrusted-text-sinks; FR3, FR10 of
 * add-project-registry.
 */
@Component
final class BoardCommand {

    private final BoardArgumentsParser argumentsParser = new BoardArgumentsParser();
    private final BoardTextRenderer textRenderer = new BoardTextRenderer();
    private final BoardJsonMapper jsonMapper = new BoardJsonMapper();
    private final InstantSource clock;
    private final FactoryProperties factoryProperties;
    private final ProjectScope scope;
    private final TrackerWiring trackerWiring;
    private final ConsoleIO console;

    BoardCommand(
            InstantSource instantSource,
            FactoryProperties factoryProperties,
            ProjectScope scope,
            TrackerWiring trackerWiring,
            ConsoleIO console) {
        this.clock = instantSource;
        this.factoryProperties = factoryProperties;
        this.scope = scope;
        this.trackerWiring = trackerWiring;
        this.console = console;
    }

    /**
     * @param args the raw application arguments, including the leading {@code board} token
     * @throws UsageException if the flags are malformed or the project has no {@code tracker:}
     *     section, or names an unregistered adapter type
     * @throws PipelineLoadFailedException if {@code .gnomish/} fails to load
     * @throws IOException if {@code .gnomish/} cannot be read (a genuine I/O fault)
     */
    void run(ApplicationArguments args) throws IOException {
        BoardArguments boardArguments = argumentsParser.parse(args, scope.registeredClone());
        ReadOnlyTrackerResolution resolution =
                trackerWiring.resolveReadOnly(boardArguments.dir(), scope.mintInstanceId());
        TrackerConfig trackerConfig = resolution.trackerConfig();

        BoardModel model = BoardComposition.compose(
                resolution.tracker(), trackerConfig, factoryProperties.tracker(), clock, boardArguments.limit());

        if (boardArguments.json()) {
            console.printMachine(jsonMapper.serialize(model) + ConsoleIO.LINE_END);
        } else {
            // The human path, and the one that matters most here: a board row carries the tracker's
            // own issue title, which is attacker-influenced text arriving at a terminal (UX2, FR5 of
            // harden-untrusted-text-sinks).
            console.print(textRenderer.render(model) + ConsoleIO.LINE_END);
        }
    }
}
