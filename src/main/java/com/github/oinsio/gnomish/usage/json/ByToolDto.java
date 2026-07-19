package com.github.oinsio.gnomish.usage.json;

/**
 * The {@code gnomish usage --json} mini-contract's per-tool usage shape — mirrors {@code
 * status.json.ByToolDto} field-for-field, as a distinct class in this package (design D5).
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 *
 * @param name the tool name
 * @param calls the number of calls this aggregate covers
 * @param totalMillis total wall time across those calls, in milliseconds
 */
public record ByToolDto(String name, int calls, long totalMillis) {}
