package com.github.oinsio.gnomish.adapter.pipeline;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the {@code config.yaml} {@code executors:} section, keyed by its name (FR9 of
 * define-executor-contract):
 *
 * <pre>{@code
 * executors:
 *   acme:
 *     type: program
 *     program: [acme-executor, --json]
 *     settings: {maxTurns: 20}
 *     credentials: [ACME_API_KEY]
 *     egress: [api.acme.example]
 * }</pre>
 *
 * <p>Every field is nullable at the wire so a missing one reaches {@link ExecutorDeclarations} as
 * {@code null} and is reported as a located error there, rather than failing the parse. {@code
 * type} is a raw string for the same reason. {@code settings} binds plain JDK types and is opaque
 * here: the executor's describe schema judges it (design D5, phase b).
 *
 * <p>Implements FR9, NFR-S3 of define-executor-contract.
 *
 * @param type the raw declaration type, or {@code null} when omitted
 * @param program the argv of a {@code program} declaration, or {@code null} when omitted
 * @param settings the opaque executor settings, or {@code null} when omitted
 * @param credentials the granted environment variable names, or {@code null} when omitted
 * @param egress the hosts the executor may reach, or {@code null} when omitted
 */
public record ExecutorDeclarationDto(
        @Nullable String type,
        @Nullable List<String> program,
        @Nullable Map<String, Object> settings,
        @Nullable List<String> credentials,
        @Nullable List<String> egress) {}
