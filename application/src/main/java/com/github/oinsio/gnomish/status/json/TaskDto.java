package com.github.oinsio.gnomish.status.json;

/**
 * The JSON contract's {@code task} section: the task's opaque id and human title.
 * {@code body} is deliberately excluded from the wire contract — the canonical
 * example carries only {@code id}/{@code title} (spec.md).
 *
 * <p>Implements FR11, M3 of add-manual-run.
 *
 * @param id the task's opaque identifier
 * @param title the task's human title
 */
public record TaskDto(String id, String title) {}
