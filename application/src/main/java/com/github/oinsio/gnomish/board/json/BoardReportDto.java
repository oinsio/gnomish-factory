package com.github.oinsio.gnomish.board.json;

import java.util.List;

/**
 * The board JSON contract's top-level document (v1): {@code version} (always
 * {@code 1}), {@code generatedAt}, {@code truncated}, the {@code ready} column
 * (summary counts + rows), and the {@code working} / {@code awaitingHuman} row
 * lists — one projection of {@code BoardModel}, sibling of {@code StatusReportDto}.
 *
 * <p>Implements FR6, NFR-O1, UX4 of add-board-command.
 *
 * @param version the contract version; always {@code 1}
 * @param generatedAt ISO-8601 UTC instant the board was observed at
 * @param truncated true when the Ready window was capped at the requested limit
 * @param ready the Ready column: summary counts, WIP-gate facts, and rows
 * @param working the Working column rows, in {@code listOpen} order
 * @param awaitingHuman the AwaitingHuman column rows, in {@code listOpen} order
 */
public record BoardReportDto(
        int version,
        String generatedAt,
        boolean truncated,
        ReadyColumnDto ready,
        List<WorkingRowDto> working,
        List<AwaitingHumanRowDto> awaitingHuman) {}
