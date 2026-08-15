package com.github.oinsio.gnomish.board.json;

/**
 * One {@code ready} row of the board JSON contract: task id, title, the
 * returned/fresh distinction, and the resolved {@link EligibilityDto}.
 *
 * <p>Implements FR2, NFR-O1 of add-board-command.
 *
 * @param id the task's canonical identity ({@code TaskRef.id()})
 * @param title the task's title
 * @param returned true when the task was previously worked and given back
 * @param eligibility the resolved eligibility: eligible, or naming the skip reason
 */
public record ReadyRowDto(String id, String title, boolean returned, EligibilityDto eligibility) {}
