package com.github.oinsio.gnomish.adapter.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The raw Jackson binding of one stream-json line, wire shape verbatim (design
 * D3): every field is optional at this layer because a line's actual shape
 * depends on its (also optional) {@code type}/{@code subtype} — {@link
 * StreamJsonParser} is the only reader of this type, and it is the one that
 * knows which fields a given {@code type} makes meaningful. {@code
 * @JsonIgnoreProperties(ignoreUnknown = true)} on every nested shape (here and
 * in {@link Message}, {@link ContentBlockWire}) is the field half of FR4's
 * tolerance contract; the parser supplies the other half by skipping lines
 * whose {@code type} it does not recognize.
 *
 * <p>Kept private to the package: adapters outside {@code adapter.agent} see
 * only {@link AgentEvent}, never this wire-shaped intermediate.
 *
 * <p>Implements FR4, D3 of add-agent-executor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record StreamJsonLine(
        @Nullable String type,
        @Nullable String subtype,
        @Nullable String session_id,
        @Nullable String model,
        @Nullable String parent_tool_use_id,
        @Nullable Message message,
        @Nullable String result,
        @Nullable Map<String, Object> usage,
        @Nullable Map<String, Object> modelUsage) {

    /**
     * The {@code message} object of an {@code assistant}/{@code user} line:
     * carries the model (assistant only) and the content-block array.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(@Nullable String model, @Nullable List<ContentBlockWire> content) {}

    /**
     * One entry of a message's {@code content} array, wire shape verbatim —
     * {@code text}/{@code tool_use}/{@code tool_result} fields coexist here
     * because Jackson binds the union untyped; {@link StreamJsonParser} narrows
     * by this record's own {@code type} field into the sealed {@link
     * ContentBlock}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentBlockWire(
            @Nullable String type,
            @Nullable String text,
            @Nullable String id,
            @Nullable String name,
            @Nullable Map<String, Object> input,
            @Nullable String tool_use_id,
            @Nullable Object content) {}
}
