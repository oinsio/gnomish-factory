package com.github.oinsio.gnomish.adapter.pipeline;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code stages/<name>/stage.yaml} wire-format DTO (D2): the eight
 * stage-contract sections as declared. The stage {@code name} is not carried
 * here — it comes from the directory name and {@code pipeline.yaml} order (FR3),
 * supplied by the loader (task 6.x). Deserialized by {@link PipelineYaml}, then
 * mapped to the pure domain {@code StageDefinition} (task 5.3).
 *
 * <p>Wire shape:
 *
 * <pre>{@code
 * purpose: "..."
 * inputs:      [ { kind: source }, { kind: internal, producerOutputId: plan-doc } ]
 * outputs:     [ { id: impl-diff } ]
 * executor:    { type: agent-cli, model: ..., settings: { ... } }
 * instructions: stages/implement/instructions.md
 * verify:      [ { type: builtin|command|external|judge, ... } ]
 * autonomy:    { attemptLimit: 5 }   # optional per-stage override
 * advancement: auto                  # auto | manual
 * }</pre>
 *
 * <p>Every field is nullable at the wire level: missing required fields are
 * carried as {@code null} for the structural-validation step to report (task
 * 5.2, FR5) rather than failing to deserialize. {@code advancement} is a raw
 * string so an unknown value becomes a reported structural error, not a
 * deserialization failure; the domain enum mapping is task 5.3.
 *
 * <p>Implements FR1, FR2, FR4 (DTO shape), D2 of load-pipeline-config.
 *
 * @param purpose the stage purpose (§1), or {@code null} when omitted
 * @param inputs the input entries in declaration order (§2), or {@code null}
 * @param outputs the output entries in declaration order (§3), or {@code null}
 * @param executor the Mechanism section (§5), or {@code null} when omitted
 * @param instructions the instructions-file path (§4), or {@code null}
 * @param verify the ordered verify entries (§6), or {@code null} when omitted
 * @param autonomy the per-stage autonomy override (§7), or {@code null} when the
 *     stage declares none (the default from {@code config.yaml} then applies)
 * @param advancement the raw advancement mode (§8), or {@code null} when omitted
 */
public record StageDto(
        @Nullable String purpose,
        @Nullable List<ArtifactInputDto> inputs,
        @Nullable List<ArtifactOutputDto> outputs,
        @Nullable ExecutorDto executor,
        @Nullable String instructions,
        @Nullable List<VerifyCheckDto> verify,
        @Nullable AutonomyDto autonomy,
        @Nullable String advancement) {}
