package com.github.oinsio.gnomish.adapter.agent;

/**
 * One content block of an {@code assistant} or {@code user} stream-json message
 * (design D3): free text, a top-level or nested tool invocation ({@link ToolUse}),
 * or that invocation's result ({@link ToolResult}). Later tasks read these blocks
 * to derive the tool trace (task 3.4, FR6) and the round's final-message summary
 * (task 3.5, FR7); this task only carries the information forward without losing
 * any of it.
 *
 * <p>A sealed hierarchy rather than one flat record: the CLI's {@code content}
 * array is heterogeneous by design (a {@code text} block never carries a tool
 * id, a {@code tool_use} block never carries result content), and a sealed type
 * lets a caller {@code switch} exhaustively instead of reading null-checked
 * fields of a lump-sum shape.
 *
 * <p>Implements FR4, D3 of add-agent-executor.
 */
public sealed interface ContentBlock {

    /**
     * A free-text block of a message's content array.
     *
     * @param text the block's text; never null (blank is accepted — an agent
     *     may emit whitespace-only text)
     */
    record Text(String text) implements ContentBlock {

        public Text {
            text = requireNonNull(text, "Text.text");
        }
    }

    /**
     * A tool invocation block, top-level or nested inside a subagent round
     * (top-level vs. nested is a property of the enclosing {@link AssistantEvent},
     * not of this block — design D3, task 3.4 filters by the event's {@code
     * parentToolUseId}).
     *
     * @param id the tool call's id, later matched against a {@link ToolResult#toolUseId()};
     *     never blank
     * @param name the tool's name; never blank
     * @param input the tool's raw input as supplied by the CLI, defensively
     *     copied and unmodifiable; never null, possibly empty
     */
    record ToolUse(String id, String name, java.util.Map<String, Object> input) implements ContentBlock {

        public ToolUse {
            id = requireNonBlank(id, "id");
            name = requireNonBlank(name, "name");
            input = java.util.Map.copyOf(input);
        }
    }

    /**
     * A tool-result block, carried on a {@code user} event, matched to its
     * {@link ToolUse} by {@code toolUseId}.
     *
     * @param toolUseId the id of the {@link ToolUse} this result answers; never blank
     * @param content the result content the CLI reported, as raw text; never null
     *     (the CLI may report an empty string)
     */
    record ToolResult(String toolUseId, String content) implements ContentBlock {

        public ToolResult {
            toolUseId = requireNonBlank(toolUseId, "toolUseId");
            content = requireNonNull(content, "ToolResult.content");
        }
    }

    /**
     * Fails fast on a blank component shared by {@link ToolUse} and {@link
     * ToolResult}: a tool identity or name blank is not a data point later tasks
     * (3.4's trace matching) could act on. Kept as an explicit static method
     * rather than inline in a compact constructor: PIT's record filter suppresses
     * all mutations inside a record's canonical constructor, which would silently
     * exempt this validation from the 100% mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("ContentBlock." + component + " must not be blank");
        }
        return value;
    }

    /**
     * Fails fast on a null component shared by {@link Text} and {@link
     * ToolResult}: the CLI may report an empty string but never omits the
     * field entirely — a null here signals a mapping bug, not wire data. Same
     * explicit-static-method rationale as {@link #requireNonBlank}.
     */
    private static String requireNonNull(String value, String component) {
        if (value == null) {
            throw new NullPointerException("ContentBlock." + component + " must not be null");
        }
        return value;
    }
}
