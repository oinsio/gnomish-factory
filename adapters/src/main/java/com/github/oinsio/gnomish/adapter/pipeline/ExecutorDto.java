package com.github.oinsio.gnomish.adapter.pipeline;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code executor} block of {@code stage.yaml} — the Mechanism section (D2,
 * D5a): the executor {@code type} ({@code api}/{@code agent-cli}/{@code program}),
 * the pinned {@code model}, opaque {@code settings}, and the {@code name} of the
 * {@code config.yaml} executor declaration the stage runs (FR9 of
 * define-executor-contract).
 *
 * <p>Wire shape:
 *
 * <pre>{@code
 * executor:
 *   type: agent-cli
 *   model: claude-sonnet-4-5
 *   settings:
 *     temperature: 0
 *   sandbox:
 *     needs: [docker-inside]
 *     requiresFresh: true
 * # or, for a law-declared program:
 * executor:
 *   type: program
 *   name: acme
 * }</pre>
 *
 * <p>{@code type} is carried as a raw string so an unknown value becomes a
 * structural error the loader reports (task 5.2, FR5) rather than a
 * deserialization failure; the domain enum mapping is task 5.3. {@code settings}
 * is typed {@code Map<String, Object>} to bind plain JDK types, never a Jackson
 * {@code JsonNode} (D5a). {@code model} presence is a validator concern (task
 * 4.4, FR11), not the DTO's. {@code sandbox} carries the stage's tighten-only
 * sandbox declarations (FR12–FR14 of add-sandbox-core); {@code null}/absent
 * means no declarations — the defaults apply.
 *
 * <p>Implements FR2, FR11 (DTO shape), D2, D5a of load-pipeline-config;
 * FR12–FR14 of add-sandbox-core (the {@code sandbox} field); FR9, FR10 of
 * define-executor-contract (the {@code name} field and the {@code program} type).
 *
 * @param type the raw executor type, or {@code null} when omitted
 * @param model the pinned model, or {@code null} when omitted
 * @param settings the opaque executor settings; {@code null}/absent means none
 * @param sandbox the stage's sandbox declarations; {@code null}/absent means none
 * @param name the executor declaration's name, or {@code null} when omitted
 */
public record ExecutorDto(
        @Nullable String type,
        @Nullable String model,
        @Nullable Map<String, Object> settings,
        @Nullable SandboxDto sandbox,
        @Nullable String name) {

    /** Convenience for construction outside Jackson binding (e.g. tests): no executor {@code name}. */
    public ExecutorDto(
            @Nullable String type,
            @Nullable String model,
            @Nullable Map<String, Object> settings,
            @Nullable SandboxDto sandbox) {
        this(type, model, settings, sandbox, null);
    }

    /** Convenience for construction outside Jackson binding (e.g. tests): no {@code sandbox} block. */
    public ExecutorDto(@Nullable String type, @Nullable String model, @Nullable Map<String, Object> settings) {
        this(type, model, settings, null);
    }
}
