package com.github.oinsio.gnomish.adapter.pipeline;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code executor} block of {@code stage.yaml} — the Mechanism section (D2,
 * D5a): the executor {@code type} ({@code api}/{@code agent-cli}), the pinned
 * {@code model}, and opaque {@code settings}.
 *
 * <p>Wire shape:
 *
 * <pre>{@code
 * executor:
 *   type: agent-cli
 *   model: claude-sonnet-4-5
 *   settings:
 *     temperature: 0
 * }</pre>
 *
 * <p>{@code type} is carried as a raw string so an unknown value becomes a
 * structural error the loader reports (task 5.2, FR5) rather than a
 * deserialization failure; the domain enum mapping is task 5.3. {@code settings}
 * is typed {@code Map<String, Object>} to bind plain JDK types, never a Jackson
 * {@code JsonNode} (D5a). {@code model} presence is a validator concern (task
 * 4.4, FR11), not the DTO's.
 *
 * <p>Implements FR2, FR11 (DTO shape), D2, D5a of load-pipeline-config.
 *
 * @param type the raw executor type, or {@code null} when omitted
 * @param model the pinned model, or {@code null} when omitted
 * @param settings the opaque executor settings; {@code null}/absent means none
 */
public record ExecutorDto(
        @Nullable String type,
        @Nullable String model,
        @Nullable Map<String, Object> settings) {}
