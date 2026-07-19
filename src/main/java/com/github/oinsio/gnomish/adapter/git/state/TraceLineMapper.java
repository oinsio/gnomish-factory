package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.ToolCall;

/**
 * Maps a domain {@link ToolCall} into its {@code trace.jsonl} line DTO —
 * the mapper counterpart of {@link TraceLineDto}, kept as a wholly separate,
 * unversioned contract (design D5).
 *
 * <p>Implements FR3 of add-git-workflow.
 */
final class TraceLineMapper {

    private TraceLineMapper() {}

    /**
     * Builds the {@code trace.jsonl} line DTO for one {@code ToolCall}.
     * {@code Instant.toString()} for the wire timestamp and {@code
     * Duration.toMillis()} for the wire duration — this project has no
     * {@code jackson-datatype-jsr310}, so neither type is ever bound
     * directly (same idiom as {@link StateJsonMapper}/{@link
     * StateUsageMapper}).
     *
     * @param call the tool call to render; never null
     * @return the equivalent {@code trace.jsonl} line DTO
     */
    static TraceLineDto toDto(ToolCall call) {
        return new TraceLineDto(
                call.seq(),
                call.tool(),
                call.start().toString(),
                call.duration().toMillis());
    }
}
