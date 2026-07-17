package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.DoNotMutate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches one Jackson-bound {@link StreamJsonLine} to its matching {@link
 * AgentEvent} variant by {@code type} (and {@code subtype} for the {@code
 * system} line), or drops it — an unknown {@code type}, an init line whose
 * {@code subtype} is not {@code "init"}, or a known type missing a field its
 * variant requires (e.g. no {@code session_id}) all degrade to "no event",
 * never an exception (FR4, D3). Split out of {@link StreamJsonParser} to keep
 * each file under the project's size cap and to isolate the one piece of real
 * dispatch logic from the line-reading loop around it.
 *
 * <p>Implements FR4, D3 of add-agent-executor.
 */
final class StreamJsonEventMapper {

    private static final Logger log = LoggerFactory.getLogger(StreamJsonEventMapper.class);

    private StreamJsonEventMapper() {}

    // M4 documented exception (see build.gradle's pitest block for the general
    // convention): PIT's EmptyObjectReturnValsMutator mutates only the value
    // handed to `areturn` at the `return skip(wire, "missing type or
    // session_id")` statement, without removing the preceding `invokestatic
    // skip` call — skip()'s logging side effect still fires under the mutant,
    // and skip() always returns Optional.empty() by construction (its own
    // final statement), so the mutant's substituted literal Optional.empty()
    // is byte-for-byte the same value skip() would have returned anyway. No
    // test can observe a difference: both the log line and the return value
    // are identical between mutant and original. A genuinely equivalent
    // mutant, not a coverage gap — the method's other mutations (the guard
    // condition itself, the switch dispatch) are unaffected and killed
    // normally by StreamJsonParserSpec.
    @DoNotMutate
    static Optional<AgentEvent> toEvent(StreamJsonLine wire) {
        String type = wire.type();
        String sessionId = wire.session_id();
        if (type == null || sessionId == null) {
            return skip(wire, "missing type or session_id");
        }
        return switch (type) {
            case "system" -> toInit(wire, sessionId);
            case "assistant" -> toAssistant(wire, sessionId);
            case "user" -> toUser(wire, sessionId);
            case "result" -> toResult(wire, sessionId);
            default -> skip(wire, "unrecognized type");
        };
    }

    // M4 documented exception, same rationale as toEvent above: the
    // EmptyObjectReturnValsMutator on `return skip(wire, "system line
    // without subtype=init or model")` leaves the skip() call (and its log
    // line) intact and only swaps the already-Optional.empty() return value
    // for an equal literal — no test can observe the difference.
    @DoNotMutate
    private static Optional<AgentEvent> toInit(StreamJsonLine wire, String sessionId) {
        String model = wire.model();
        if (!"init".equals(wire.subtype()) || model == null) {
            return skip(wire, "system line without subtype=init or model");
        }
        return present(new AgentEvent.InitEvent(sessionId, model));
    }

    private static Optional<AgentEvent> toAssistant(StreamJsonLine wire, String sessionId) {
        String model = wire.message() == null ? null : wire.message().model();
        return present(new AgentEvent.AssistantEvent(sessionId, wire.parent_tool_use_id(), model, contentOf(wire)));
    }

    private static Optional<AgentEvent> toUser(StreamJsonLine wire, String sessionId) {
        return present(new AgentEvent.UserEvent(sessionId, wire.parent_tool_use_id(), contentOf(wire)));
    }

    // M4 documented exception, same rationale as toEvent/toInit above: the
    // EmptyObjectReturnValsMutator on `return skip(wire, "result line
    // without a result field")` leaves the skip() call (and its log line)
    // intact and only swaps the already-Optional.empty() return value for
    // an equal literal — no test can observe the difference.
    @DoNotMutate
    private static Optional<AgentEvent> toResult(StreamJsonLine wire, String sessionId) {
        String result = wire.result();
        if (result == null) {
            return skip(wire, "result line without a result field");
        }
        return present(new AgentEvent.ResultEvent(sessionId, result, wire.usage(), wire.modelUsage()));
    }

    private static List<ContentBlock> contentOf(StreamJsonLine wire) {
        StreamJsonLine.Message message = wire.message();
        List<StreamJsonLine.ContentBlockWire> content = message == null ? null : message.content();
        if (content == null) {
            return List.of();
        }
        return content.stream()
                .map(StreamJsonEventMapper::toContentBlock)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static @Nullable ContentBlock toContentBlock(StreamJsonLine.ContentBlockWire block) {
        String type = block.type();
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "text" -> toText(block);
            case "tool_use" -> toToolUse(block);
            case "tool_result" -> toToolResult(block);
            default -> null;
        };
    }

    private static @Nullable ContentBlock toText(StreamJsonLine.ContentBlockWire block) {
        String text = block.text();
        return text == null ? null : new ContentBlock.Text(text);
    }

    private static @Nullable ContentBlock toToolUse(StreamJsonLine.ContentBlockWire block) {
        String id = block.id();
        String name = block.name();
        if (id == null || name == null) {
            return null;
        }
        Map<String, Object> input = block.input() == null ? Map.of() : block.input();
        return new ContentBlock.ToolUse(id, name, input);
    }

    private static @Nullable ContentBlock toToolResult(StreamJsonLine.ContentBlockWire block) {
        String toolUseId = block.tool_use_id();
        if (toolUseId == null) {
            return null;
        }
        String content = block.content() == null ? "" : String.valueOf(block.content());
        return new ContentBlock.ToolResult(toolUseId, content);
    }

    private static Optional<AgentEvent> present(AgentEvent event) {
        return Optional.of(event);
    }

    private static Optional<AgentEvent> skip(StreamJsonLine wire, String reason) {
        log.debug("stream-json: skipping line of type '{}' ({})", wire.type(), reason);
        return Optional.empty();
    }
}
