package com.github.oinsio.gnomish.board.json;

import java.util.List;

/**
 * The board JSON contract's {@code ready} section: the FR3 summary counts,
 * reconciled exactly as {@code ReadySummary} reconciles them, plus the WIP-gate
 * facts a WIP-held row's reason depends on ({@code openFrontCount}, {@code
 * wipLimit} — NFR-O1's "the WIP gate is expressed as the observed open-front
 * count against the limit, none left to be recomputed"), and the row list.
 *
 * <p>{@code openFrontCount} is {@code working.size() + awaitingHuman.size()} of
 * the same document — the {@code listOpen} result the board already fetched
 * (design D7); {@code wipLimit} is not stored on {@code BoardModel} itself (it is
 * consumed transiently while resolving each row's eligibility), so {@link
 * BoardJsonMapper#toDto} takes it as an explicit parameter, mirroring how {@code
 * BoardModel.build} itself takes it as an explicit parameter rather than reading
 * configuration.
 *
 * <p>Implements FR3, NFR-O1 of add-board-command.
 *
 * @param queuedCount ready tasks in the fetched window
 * @param eligibleNowCount tasks the feed would claim now
 * @param inBackoffCount tasks currently backed off
 * @param finishedCount tasks whose recorded history is terminal
 * @param wipHeldCount fresh tasks skipped for the WIP gate
 * @param openFrontCount the observed open-front count the WIP gate was evaluated against
 * @param wipLimit the configured WIP limit the WIP gate was evaluated against
 * @param rows the Ready column's rows, in {@code listReady} order
 */
public record ReadyColumnDto(
        int queuedCount,
        int eligibleNowCount,
        int inBackoffCount,
        int finishedCount,
        int wipHeldCount,
        int openFrontCount,
        int wipLimit,
        List<ReadyRowDto> rows) {}
