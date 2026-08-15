package com.github.oinsio.gnomish.board;

import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import com.github.oinsio.gnomish.app.take.BackoffPolicy;
import java.time.Duration;
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
 * {@link EligibilityPolicy}) when {@link #build(List, List, boolean,
 * Instant, Duration, Duration, Instant, int, int)} is used; the shorter
 * {@link #build(List, List, boolean, Instant)} overload defaults every row
 * to eligible, for callers that only need the Working/AwaitingHuman columns
 * or row ordering. Either way, {@link ReadySummary#tally(List)} reconciles
 * the built {@code readyRows} into the full FR3 breakdown — with the
 * shorter overload, every row is eligible, so the ineligible counts are all
 * zero.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR2, FR3, FR4, FR5, NFR-P1 of add-board-command.
 *
 * @param readyRows the Ready column, in {@code listReady} order; defensively
 *     copied, unmodifiable
 * @param workingRows the Working column, in {@code listOpen} order;
 *     defensively copied, unmodifiable
 * @param awaitingHumanRows the AwaitingHuman column, in {@code listOpen}
 *     order; defensively copied, unmodifiable
 * @param summary the Ready column's summary counts; never null
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
     * Builds a {@code BoardModel} from one {@code listReady} result and one
     * {@code listOpen} result. Ready rows preserve {@code ready}'s order
     * one-to-one, each defaulted to eligible (task 2.2's seam). Open rows
     * route by {@link TrackerTaskState}: {@code Working} entries become
     * {@link WorkingRow}s, {@code AwaitingHuman} entries become {@link
     * AwaitingHumanRow}s, both in {@code open}'s original order — {@code
     * Ready}/{@code Finished}/{@code Gone} never appear in a {@code
     * listOpen} result ({@link
     * com.github.oinsio.gnomish.app.port.tracker.Tracker#listOpen()}
     * contract) and are rejected defensively rather than silently dropped.
     *
     * @param ready the {@code listReady} result, in adapter queue order;
     *     never null
     * @param open the {@code listOpen} result, in adapter order; never null
     * @param truncated whether the ready window was capped at the requested
     *     limit; passed through unchanged
     * @param generatedAt the observation instant; never null
     * @return the assembled model
     */
    public static BoardModel build(List<ReadyTask> ready, List<OpenTask> open, boolean truncated, Instant generatedAt) {
        return build(
                ready,
                open,
                truncated,
                generatedAt,
                BackoffPolicy.DEFAULT_BASE,
                BackoffPolicy.DEFAULT_CAP,
                generatedAt,
                open.size(),
                Integer.MAX_VALUE);
    }

    /**
     * Builds a {@code BoardModel} with real Ready-row eligibility annotations
     * (design D7, task 2.2): each row's {@link ReadyRow#eligibilityReason()}
     * is resolved by {@link EligibilityPolicy#resolve} in the feed's own
     * precedence — in backoff, then {@code finished}, then WIP-held —
     * without reimplementing {@code FeedPolicy}'s claim-selection logic.
     * Open-row routing is identical to {@link #build(List, List, boolean,
     * Instant)}.
     *
     * @param ready the {@code listReady} result, in adapter queue order;
     *     never null
     * @param open the {@code listOpen} result, in adapter order; never null
     * @param truncated whether the ready window was capped at the requested
     *     limit; passed through unchanged
     * @param generatedAt the observation instant; never null
     * @param base the backoff base for a single abort, resolved exactly as
     *     the take feed resolves it; never null
     * @param cap the maximum backoff delay, resolved exactly as the take
     *     feed resolves it; never null
     * @param now the instant to evaluate backoff against; never null
     * @param openFrontCount the open-front count, i.e. {@code open.size()};
     *     the caller supplies it explicitly to mirror {@code FeedPolicy}'s
     *     parameter shape
     * @param wipLimit the configured WIP limit, resolved exactly as the feed
     *     resolves it
     * @return the assembled model
     */
    public static BoardModel build(
            List<ReadyTask> ready,
            List<OpenTask> open,
            boolean truncated,
            Instant generatedAt,
            Duration base,
            Duration cap,
            Instant now,
            int openFrontCount,
            int wipLimit) {
        List<ReadyRow> readyRows = new ArrayList<>(ready.size());
        for (ReadyTask task : ready) {
            EligibilityReason reason = EligibilityPolicy.resolve(task, base, cap, now, openFrontCount, wipLimit);
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
                readyRows, workingRows, awaitingHumanRows, ReadySummary.tally(readyRows), truncated, generatedAt);
    }

    private static IllegalStateException unexpectedOpenState(OpenTask task) {
        return new IllegalStateException("listOpen contract violation: " + task.ref() + " carries state " + task.state()
                + ", only Working/AwaitingHuman are allowed");
    }
}
