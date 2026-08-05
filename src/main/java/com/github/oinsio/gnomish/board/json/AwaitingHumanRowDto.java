package com.github.oinsio.gnomish.board.json;

/**
 * One {@code awaitingHuman} row of the board JSON contract: task id, title, and the
 * lowercase park-reason label ({@code "escalation"} / {@code "infra"} / {@code
 * "checkpoint"}), matching the spec's own park-reason scenario wording — the same
 * labels {@code BoardTextFormatter.parkReasonLabel} renders in text.
 *
 * <p>Implements FR5, NFR-O1 of add-board-command.
 *
 * @param id the task's canonical identity ({@code TaskRef.id()})
 * @param title the task's title
 * @param parkReason the lowercase park-reason label
 */
public record AwaitingHumanRowDto(String id, String title, String parkReason) {}
