package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.daemon.LoopOrder;
import com.github.oinsio.gnomish.app.daemon.LoopShape;
import com.github.oinsio.gnomish.app.daemon.LoopWait;
import com.github.oinsio.gnomish.app.daemon.RestartPolicy;
import com.github.oinsio.gnomish.app.daemon.SupervisedLoop;
import com.github.oinsio.gnomish.app.project.ProjectLayout;
import com.github.oinsio.gnomish.atomicfile.AtomicFileWriter;
import com.github.oinsio.gnomish.board.BoardComposition;
import com.github.oinsio.gnomish.board.BoardModel;
import com.github.oinsio.gnomish.dashboard.BoardSectionView;
import com.github.oinsio.gnomish.dashboard.DashboardBoardCache;
import com.github.oinsio.gnomish.dashboard.DashboardRenderCycle;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.status.DaemonComponent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one dashboard assembly (design D10 of supervise-daemon-loops-and-embed-dashboard), built once
 * per command — the standalone {@code gnomish dashboard} and the page inside {@code serve}. It owns
 * the page's output path (an override, or {@link #DEFAULT_FILE_NAME} in the instance's serve
 * directory), the ready window ({@link #BOARD_READY_LIMIT}), the board fetch over the {@link
 * BoardSource} it is handed, the render cycle, the board cache and the watch render loop.
 *
 * <p><b>The watch loop is a supervised daemon loop</b> (D7): tick → wait on a {@link
 * LoopWait.FixedInterval} of {@link #RENDER_CADENCE}, framed as {@link DaemonComponent#DASHBOARD},
 * under {@link RestartPolicy.Bounded} — the page is optional, so more than five deaths in ten
 * minutes disable it with one ERROR rather than respawning forever. A tick re-renders on every
 * cycle but re-fetches the board only every {@link #BOARD_CADENCE}, the {@link DashboardBoardCache}
 * carrying the last model between (FR9, NFR-P1 of add-dashboard-page). A data-source failure
 * degrades its own section and an output-write failure is one coded WARN; neither is a death, so a
 * tracker outage never disables the page.
 *
 * <p>Implements FR9, FR14 of supervise-daemon-loops-and-embed-dashboard. Implements FR7, FR8, FR9,
 * NFR-P1, NFR-R1, NFR-R2 of add-dashboard-page; FR10 of add-project-registry.
 */
public final class DashboardWatch {

    private static final Logger log = LoggerFactory.getLogger(DashboardWatch.class);

    /** The page's file name in the instance's serve directory (design D8 of add-dashboard-page). */
    static final String DEFAULT_FILE_NAME = "dashboard.html";

    /** The render/re-render cadence (design D4 of add-dashboard-page): 10 seconds. */
    public static final Duration RENDER_CADENCE = Duration.ofSeconds(10);

    /** The tracker board's own, slower refresh cadence (design D4 of add-dashboard-page): 60 seconds. */
    public static final Duration BOARD_CADENCE = Duration.ofSeconds(60);

    /** The {@code listReady} window of the page's board. */
    private static final int BOARD_READY_LIMIT = 50;

    /** The longest wait before a respawn (design D7 of supervise-daemon-loops-and-embed-dashboard). */
    private static final Duration RESTART_BACKOFF_CAP = Duration.ofMinutes(10);

    /** The restarts the Bounded policy allows within {@link #RESTART_WINDOW} (design D7). */
    private static final int MAX_RESTARTS = 5;

    private static final Duration RESTART_WINDOW = Duration.ofMinutes(10);

    private final Path outputFile;
    private final DashboardRenderCycle renderCycle;
    private final BoardSource source;
    private final InstantSource clock;
    private final DashboardBoardCache boardCache = new DashboardBoardCache();
    private final SupervisedLoop loop;
    private final AtomicBoolean finalRendered = new AtomicBoolean();

    /**
     * @param layout the project's folder layout, supplying the instance's serve directory; never null
     * @param instanceName the configured instance name; one path segment
     * @param outOverride the operator's {@code --out} path, or {@code null} for the default
     * @param source the tracker client and the configuration the board is composed from; never null
     * @param time the time equipment (virtual under test): its clock is every render's observation
     *     instant and the restart window's, its sleeper waits the render cadence and the backoff
     *     (design D20 of supervise-daemon-loops-and-embed-dashboard); never null
     */
    public DashboardWatch(
            ProjectLayout layout,
            String instanceName,
            @Nullable Path outOverride,
            BoardSource source,
            TimeEquipment time) {
        Path serveDir = layout.serveDir(instanceName);
        this.outputFile = outOverride != null ? outOverride : serveDir.resolve(DEFAULT_FILE_NAME);
        this.renderCycle = new DashboardRenderCycle(serveDir);
        this.source = source;
        this.clock = time.clock();
        LoopShape shape = new LoopShape(
                DaemonComponent.DASHBOARD,
                LoopOrder.TICK_THEN_WAIT,
                new LoopWait.FixedInterval(time.sleeper(), RENDER_CADENCE),
                new RestartPolicy.Bounded(
                        RENDER_CADENCE, RESTART_BACKOFF_CAP, MAX_RESTARTS, RESTART_WINDOW, time.clock()));
        this.loop = new SupervisedLoop(shape, this::tick, time);
    }

    /** Returns the path every render writes the page to; never null. */
    public Path outputFile() {
        return outputFile;
    }

    /**
     * Renders the page once, synchronously, with a fresh board fetch and no meta-refresh — the
     * one-shot {@code gnomish dashboard} (FR1, FR8 of add-dashboard-page). A board failure degrades
     * its section; a write failure is the caller's to report.
     *
     * @throws IOException if the page cannot be written to {@link #outputFile()}
     */
    public void renderOnce() throws IOException {
        Instant now = clock.instant();
        BoardSectionView boardView = new DashboardBoardCache().refresh(this::fetchBoard, now);
        AtomicFileWriter.write(outputFile, renderCycle.render(boardView, now, null));
    }

    /** Starts the watch render loop: one render now, then one every {@link #RENDER_CADENCE}. */
    public void start() {
        loop.start();
    }

    /**
     * Blocks until the watch loop has ended.
     *
     * @return true if the loop ended because it kept dying and was disabled (FR9)
     */
    public boolean awaitEnd() {
        return loop.awaitEnd();
    }

    /**
     * Stops the watch loop, waits out a render in progress, then renders once more on the calling
     * thread, so the page reads the state its owner left last (design D9, the {@code stopped}
     * snapshot). Only the first call renders: {@code serve}'s drain body and its shutdown hook can
     * both reach this, and a later pass must not re-stamp the final page (FR11, UX2 of
     * supervise-daemon-loops-and-embed-dashboard). The claim is an atomic flag, no lock, so a
     * second caller never waits behind the first one's render (lock-scope.md).
     *
     * <p>The render runs on the caller's thread — the drain body or the JVM shutdown hook — outside
     * the loop's guard, so it guards itself (NFR-R1): it renders the cached board with no tracker
     * call, so no network deadline sits inside the teardown, and any failure, an {@link Error}
     * included, is one coded WARN rather than an exception that would skip the logging stop after it
     * or fail a completed drain.
     */
    public void stopAndRenderFinal() {
        loop.stopAndJoin();
        if (finalRendered.compareAndSet(false, true)) {
            renderFinal();
        }
    }

    private void renderFinal() {
        try {
            write(boardCache.cached(), clock.instant());
        } catch (Throwable failure) {
            log.warn(
                    OperatorEvent.DASHBOARD_FINAL_RENDER_FAILED.head()
                            + "final dashboard render failed; the page keeps its last render",
                    failure);
        }
    }

    // Package-private: specs drive the watch cycle one tick at a time, on virtual time.
    void tick() {
        Instant now = clock.instant();
        write(
                boardCache.dueFor(now, BOARD_CADENCE) ? boardCache.refresh(this::fetchBoard, now) : boardCache.cached(),
                now);
    }

    private void write(BoardSectionView boardView, Instant now) {
        String html = renderCycle.render(boardView, now, RENDER_CADENCE);
        try {
            AtomicFileWriter.write(outputFile, html);
        } catch (IOException writeFailure) {
            log.warn(
                    OperatorEvent.DASHBOARD_RENDER_WRITE_FAILED.head()
                            + "dashboard render write to {} failed; continuing the watch loop",
                    outputFile,
                    writeFailure);
        }
    }

    private BoardModel fetchBoard() {
        return BoardComposition.compose(
                source.tracker(), source.trackerConfig(), source.trackerProperties(), clock, BOARD_READY_LIMIT);
    }
}
