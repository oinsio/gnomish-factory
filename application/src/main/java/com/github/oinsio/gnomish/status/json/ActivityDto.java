package com.github.oinsio.gnomish.status.json;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code activity} section: {@code executing},
 * {@code verifying(checkRef)}, or {@code awaitingInput(prompt)}, every variant
 * carrying {@code since} as an ISO-8601 UTC string (spec.md). Live-only; the
 * whole section is {@code null} when idle.
 *
 * <p>Implements FR11, M3 of add-manual-run.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ActivityDto.Executing.class, name = "executing"),
    @JsonSubTypes.Type(value = ActivityDto.Verifying.class, name = "verifying"),
    @JsonSubTypes.Type(value = ActivityDto.AwaitingInput.class, name = "awaitingInput")
})
public sealed interface ActivityDto {

    /**
     * An executor round is in flight, optionally carrying live tool detail (FR7,
     * D10, D12 of add-agent-executor): {@code currentTool} names the top-level
     * tool call presently running and {@code toolCalls} counts top-level tool
     * calls started so far this round. Both are {@code null} when no live detail
     * has been reported (an interactive round, or before the first tool starts).
     *
     * @param type the discriminator, always {@code "executing"}
     * @param since ISO-8601 UTC instant this activity began
     * @param currentTool the name of the top-level tool call presently running,
     *     or {@code null} when unreported
     * @param toolCalls the count of top-level tool calls started so far this
     *     round, or {@code null} when unreported
     */
    record Executing(
            String type,
            String since,
            @Nullable String currentTool,
            @Nullable Integer toolCalls) implements ActivityDto {}

    /**
     * A verify check is in flight, naming which check.
     *
     * @param type the discriminator, always {@code "verifying"}
     * @param checkRef the label of the check currently running
     * @param since ISO-8601 UTC instant this activity began
     */
    record Verifying(String type, String checkRef, String since) implements ActivityDto {}

    /**
     * An operator prompt is pending an answer.
     *
     * @param type the discriminator, always {@code "awaitingInput"}
     * @param prompt the prompt text shown to the operator
     * @param since ISO-8601 UTC instant this activity began
     */
    record AwaitingInput(String type, String prompt, String since) implements ActivityDto {}
}
