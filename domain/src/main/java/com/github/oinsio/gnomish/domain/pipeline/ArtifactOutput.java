package com.github.oinsio.gnomish.domain.pipeline;

/**
 * An artifact a stage produces, declared by its stable {@code id} — the handle
 * that later stages' {@link ArtifactInput.Internal} inputs reference (FR4).
 * The record is inert data: pipeline-wide id uniqueness and reference
 * resolution are the DAG validator's concerns (design D4, task 4.3), reported
 * as located {@link ConfigError}s — never a throwing constructor, which would
 * destroy an invalid value before the validator could see and report it.
 *
 * <p>Implements FR4 of load-pipeline-config.
 *
 * @param id the stable artifact identifier, unique across the whole pipeline
 *     (uniqueness validated by task 4.3, not here)
 */
public record ArtifactOutput(String id) {}
