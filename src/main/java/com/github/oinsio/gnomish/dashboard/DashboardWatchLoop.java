package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.board.BoardModel;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.serveobservability.writer.AtomicFileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code --watch} render loop (task 4.4): re-renders the page on {@link #RENDER_CADENCE},
 * baking a matching meta-refresh via {@link DashboardHtmlRenderer} (FR7), while the tracker board
 * is only re-fetched every {@link #BOARD_CADENCE} — a {@link DashboardBoardCache} carries the last
 * model between board refreshes so the render cadence never scales tracker reads (FR9, NFR-P1,
 * design D4). Every data-source failure — a snapshot/ledger read or a board fetch — degrades its
 * own section via {@link DashboardRenderCycle}/{@link DashboardBoardCache} and the loop keeps
 * running; an output-file write failure is logged and the loop keeps running rather than exiting
 * (NFR-R1). Runs until the calling thread is interrupted or the process is killed — {@code
 * gnomish dashboard --watch} has no drain/shutdown protocol of its own, unlike {@code serve}.
 *
 * <p>Implements FR7, FR8, FR9, NFR-P1, NFR-R1, NFR-R2 of add-dashboard-page (design D4, D9).
 */
public final class DashboardWatchLoop {

    private static final Logger log = LoggerFactory.getLogger(DashboardWatchLoop.class);

    /** The render/re-render cadence (design D4, closes Q1): 10 seconds. */
    public static final Duration RENDER_CADENCE = Duration.ofSeconds(10);

    /** The tracker board's own, slower refresh cadence (design D4, closes Q2): 60 seconds. */
    public static final Duration BOARD_CADENCE = Duration.ofSeconds(60);

    private final DashboardRenderCycle renderCycle;
    private final DashboardBoardCache boardCache = new DashboardBoardCache();
    private final Sleeper sleeper;
    private final Clock clock;

    /**
     * @param renderCycle the shared render composition; never null
     * @param sleeper the render-cadence sleeper — production {@code ThreadSleeper}, a controllable
     *     sleeper under test; never null
     * @param clock the wall-clock time source for every cycle's observation instant; never null
     */
    public DashboardWatchLoop(DashboardRenderCycle renderCycle, Sleeper sleeper, Clock clock) {
        this.renderCycle = renderCycle;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /**
     * Runs cycles until the calling thread is interrupted: render, write, sleep {@link
     * #RENDER_CADENCE}, repeat. The interrupt check on every cycle is what makes the documented
     * "runs until the calling thread is interrupted" contract real — {@code ThreadSleeper}
     * restores the interrupt flag rather than throwing, so without the check nothing between
     * cycles would ever observe the interrupt and the loop could only die with the process. It
     * also keeps every covering test bounded: a PIT mutant that drops the {@code sleep} call
     * turns this into a busy-render loop that only an interrupt can stop.
     *
     * @param homeDir the user's home directory the observability files live under; never null
     * @param instanceName the configured instance name; never null
     * @param outputFile the page's output path; never null
     * @param boardFetch the board composition call, re-run on {@link #BOARD_CADENCE}; never null
     */
    public void run(Path homeDir, String instanceName, Path outputFile, Supplier<BoardModel> boardFetch) {
        while (!Thread.currentThread().isInterrupted()) {
            renderOnce(homeDir, instanceName, outputFile, boardFetch);
            sleeper.sleep(RENDER_CADENCE);
        }
    }

    /**
     * Runs exactly one cycle. Package-private so specs drive the loop deterministically, one cycle
     * at a time, mirroring {@code FeedAutomaton.step()}.
     */
    void renderOnce(Path homeDir, String instanceName, Path outputFile, Supplier<BoardModel> boardFetch) {
        Instant now = clock.instant();
        BoardSectionView boardView =
                boardCache.dueFor(now, BOARD_CADENCE) ? boardCache.refresh(boardFetch, now) : boardCache.cached();
        String html = renderCycle.render(homeDir, instanceName, boardView, now, RENDER_CADENCE);
        try {
            AtomicFileWriter.write(outputFile, html);
        } catch (IOException writeFailure) {
            log.warn("dashboard render write to {} failed; continuing the watch loop", outputFile, writeFailure);
        }
    }
}
