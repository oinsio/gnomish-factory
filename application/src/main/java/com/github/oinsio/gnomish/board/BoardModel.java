package com.github.oinsio.gnomish.board;

import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The board's single immutable model: three columns (Ready, Working,
 * AwaitingHuman) built from exactly one {@code listReady} and one {@code
 * listOpen} result — the two port calls NFR-P1 caps a board invocation to —
 * plus the ready-window summary, the {@code truncated} flag, and the
 * observation instant (design D5). {@link #build} performs no reordering,
 * filtering beyond state routing, or deduplication, so rows in every column
 * preserve the adapter's original list order and the model is deterministic
 * for a fixed tracker state (FR2–FR5).
 *
 * <p>{@code truncated} and {@code generatedAt} are simple pass-throughs from
 * the caller: this task computes neither — {@code truncated} is decided by
 * the CLI layer comparing the fetched count to the requested limit (task
 * 3.2), and {@code generatedAt} is the caller's observation instant.
 *
 * <p>Each {@link ReadyRow} carries the real eligibility reason (design D7,
 * {@link EligibilityPolicy}), and {@link ReadySummary#tally(List)} reconciles
 * the built {@code readyRows} into the full FR3 breakdown.
 *
 * <p>The model also carries the WIP limit its WIP-held rows were judged by
 * ({@code wipLimit}, the very {@link EligibilityInputs#wipLimit()} handed to
 * {@link #build}) and derives the open-front count from its own columns
 * ({@link #openFrontCount()}), so every renderer of the board — the JSON
 * contract, the dashboard's WIP stat — reads both from here rather than
 * taking a second copy of the limit beside the model (design D13). There is
 * deliberately no {@code build} overload without {@link EligibilityInputs}:
 * it would be a second path that puts a made-up limit on the page.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR2, FR3, FR4, FR5, NFR-P1 of add-board-command; FR6 of
 * add-parameter-count-gate (the five-parameter {@code build}); FR12, FR14 of
 * supervise-daemon-loops-and-embed-dashboard ({@code wipLimit},
 * {@link #openFrontCount()}).
 *
 * @param readyRows the Ready column, in {@code listReady} order; defensively
 *     copied, unmodifiable
 * @param workingRows the Working column, in {@code listOpen} order;
 *     defensively copied, unmodifiable
 * @param awaitingHumanRows the AwaitingHuman column, in {@code listOpen}
 *     order; defensively copied, unmodifiable
 * @param summary the Ready column's summary counts; never null
 * @param wipLimit the WIP limit the Ready rows' WIP-held eligibility was
 *     judged against
 * @param truncated true when the ready window was capped at the requested
 *     limit; passed through unchanged
 * @param generatedAt the observation instant this model was built at; never
 *     null
 */
public record BoardModel(
        List<ReadyRow> readyRows,
        List<WorkingRow> workingRows,
        List<AwaitingHumanRow> awaitingHumanRows,
        ReadySummary summary,
        int wipLimit,
        boolean truncated,
        Instant generatedAt) {

    public BoardModel {
        readyRows = List.copyOf(readyRows);
        workingRows = List.copyOf(workingRows);
        awaitingHumanRows = List.copyOf(awaitingHumanRows);
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(generatedAt, "generatedAt");
    }

    /**
     * The open front this board observed: its {@code Working} plus its
     * {@code AwaitingHuman} rows, i.e. the size of the {@code listOpen}
     * result the model was built from.
     *
     * @return the open-front count
     */
    public int openFrontCount() {
        return workingRows.size() + awaitingHumanRows.size();
    }

    /**
     * Builds a {@code BoardModel} with real Ready-row eligibility annotations
     * (design D7, task 2.2): each row's {@link ReadyRow#eligibilityReason()}
     * is resolved by {@link EligibilityPolicy#resolve} in the feed's own
     * precedence — in backoff, then {@code finished}, then WIP-held —
     * without reimplementing {@code FeedPolicy}'s claim-selection logic.
     * Open rows route by {@link TrackerTaskState}: {@code Working} entries
     * become {@link WorkingRow}s, {@code AwaitingHuman} entries become {@link
     * AwaitingHumanRow}s, both in {@code open}'s original order — {@code
     * Ready}/{@code Finished}/{@code Gone} never appear in a {@code
     * listOpen} result ({@link
     * com.github.oinsio.gnomish.app.port.tracker.Tracker#listOpen()}
     * contract) and are rejected defensively rather than silently dropped.
     * The model keeps {@code eligibility.wipLimit()} as its {@code wipLimit}.
     *
     * @param ready the {@code listReady} result, in adapter queue order;
     *     never null
     * @param open the {@code listOpen} result, in adapter order; never null
     * @param truncated whether the ready window was capped at the requested
     *     limit; passed through unchanged
     * @param generatedAt the observation instant, and the instant every
     *     ready row's backoff is evaluated at; never null
     * @param eligibility the backoff shape, open-front count and WIP limit
     *     every ready row is judged against, resolved exactly as the take
     *     feed resolves them; its WIP limit becomes the model's {@code
     *     wipLimit}; never null
     * @return the assembled model
     */
    public static BoardModel build(
            List<ReadyTask> ready,
            List<OpenTask> open,
            boolean truncated,
            Instant generatedAt,
            EligibilityInputs eligibility) {
        List<ReadyRow> readyRows = new ArrayList<>(ready.size());
        for (ReadyTask task : ready) {
            EligibilityReason reason = EligibilityPolicy.resolve(task, eligibility, generatedAt);
            readyRows.add(new ReadyRow(task.ref(), task.title(), task.returned(), reason));
        }

        List<WorkingRow> workingRows = new ArrayList<>();
        List<AwaitingHumanRow> awaitingHumanRows = new ArrayList<>();
        for (OpenTask task : open) {
            switch (task.state()) {
                case TrackerTaskState.Working working ->
                    workingRows.add(new WorkingRow(task.ref(), task.title(), working.holder(), task.claimVersion()));
                case TrackerTaskState.AwaitingHuman awaitingHuman ->
                    awaitingHumanRows.add(new AwaitingHumanRow(task.ref(), task.title(), awaitingHuman.reason()));
                default -> throw unexpectedOpenState(task);
            }
        }

        return new BoardModel(
                readyRows,
                workingRows,
                awaitingHumanRows,
                ReadySummary.tally(readyRows),
                eligibility.wipLimit(),
                truncated,
                generatedAt);
    }

    private static IllegalStateException unexpectedOpenState(OpenTask task) {
        return new IllegalStateException("listOpen contract violation: " + task.ref() + " carries state " + task.state()
                + ", only Working/AwaitingHuman are allowed");
    }
}
