package com.github.oinsio.gnomish.adapter.git.state;

/**
 * The {@code state.json} contract's per-tool usage shape carried under an
 * executor usage's {@code byTool} array, mirroring the domain's {@code
 * ToolUsage} — the same shape {@code status.json}'s {@code ByToolDto} renders,
 * as a distinct class in this package (design D5): {@code name}, {@code calls},
 * {@code totalMillis}.
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 *
 * @param name the tool name
 * @param calls the number of calls this aggregate covers
 * @param totalMillis total wall time across those calls, in milliseconds
 */
public record StateByToolDto(String name, int calls, long totalMillis) {}
