package com.github.oinsio.gnomish.serveobservability.json;

/**
 * The JSON contract's {@code vitals.janitor} entry (FR7): {@code lastRunAt}.
 *
 * @param lastRunAt ISO-8601 UTC instant of the janitor's last completed sweep
 */
public record JanitorDto(String lastRunAt) {}
