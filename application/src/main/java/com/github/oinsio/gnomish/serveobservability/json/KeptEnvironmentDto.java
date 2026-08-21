package com.github.oinsio.gnomish.serveobservability.json;

/**
 * The JSON contract's {@code vitals.sweep.kept} entry (NFR-O1 of add-serve-sandbox-lifecycle):
 * one kept environment waiting for a resume, with its age and the margin left before the aged
 * reaper disposes it.
 *
 * @param taskKey the sanitized environment key the kept objects belong to
 * @param ageSeconds how old the kept environment is
 * @param untilReapSeconds how long until the aged reaper disposes it
 */
public record KeptEnvironmentDto(String taskKey, long ageSeconds, long untilReapSeconds) {}
