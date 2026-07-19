package com.github.oinsio.gnomish.adapter.git.state;

/**
 * The {@code trace.jsonl} contract's per-line shape: one compact JSON object
 * per {@code ToolCall}, written one per line. Unlike {@code task.json} and
 * {@code state.json}, this contract carries no {@code "version"} field —
 * resume never reads {@code trace.jsonl} (design D5), so there is nothing to
 * gate.
 *
 * <p>Implements FR3 of add-git-workflow.
 *
 * @param seq the call's chronological position within the trace; never negative
 * @param tool the tool name; never blank
 * @param start ISO-8601 UTC instant the call started
 * @param durationMillis how long the call took, in milliseconds; never negative
 */
public record TraceLineDto(int seq, String tool, String start, long durationMillis) {}
