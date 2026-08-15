package com.github.oinsio.gnomish.status.json;

/**
 * The JSON contract's per-tool usage shape carried under an executor usage's
 * {@code byTool} array, mirroring the domain's {@code ToolUsage}: {@code name},
 * {@code calls}, {@code totalMillis} (spec.md).
 *
 * <p>Implements FR11, M3 of add-manual-run.
 *
 * @param name the tool name
 * @param calls the number of calls this aggregate covers
 * @param totalMillis total wall time across those calls, in milliseconds
 */
public record ByToolDto(String name, int calls, long totalMillis) {}
