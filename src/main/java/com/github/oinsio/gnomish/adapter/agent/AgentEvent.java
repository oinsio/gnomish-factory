package com.github.oinsio.gnomish.adapter.agent;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One recognized line of the Claude Code CLI's {@code --output-format
 * stream-json --verbose} protocol (design D3): the {@code system}/{@code init}
 * event, an {@code assistant} or {@code user} message event, or the round's
 * {@code result} event. {@link StreamJsonParser} is the only producer of this
 * type — it dispatches a parsed line to the matching variant when {@code type}
 * (and {@code subtype} where relevant) matches, and silently drops everything
 * else (unknown types, malformed lines) rather than surfacing a fifth,
 * catch-all variant (FR4).
 *
 * <p>A sealed hierarchy over one flat "event" record: the four kinds carry
 * disjoint fields (only {@code assistant}/{@code user} carry {@code
 * parentToolUseId}; only {@code result} carries {@code usage}), and later tasks
 * (3.2's result handling, 3.4's trace filtering) read a specific variant, not a
 * lump-sum shape with fields that are only sometimes meaningful.
 *
 * <p>Implements FR4, D3 of add-agent-executor.
 */
public sealed interface AgentEvent {

    /**
     * The {@code system}/{@code init} event that opens a round: the resolved
     * main model and session id later tasks use as the token-mapping fallback
     * (task 3.3, FR5) and the {@code RoundStarted} progress payload (task 3.5,
     * FR7).
     *
     * @param sessionId the CLI's session id for this round; never blank
     * @param model the round's main model id; never blank
     */
    record InitEvent(String sessionId, String model) implements AgentEvent {

        public InitEvent {
            sessionId = requireNonBlank(sessionId, "sessionId");
            model = requireNonBlank(model, "model");
        }
    }

    /**
     * An {@code assistant} message event: the agent's own turn, carrying text
     * and/or tool-use content blocks. {@code parentToolUseId} distinguishes a
     * top-level call ({@code null}) from one nested inside a subagent round (the
     * nested call's enclosing {@code Task} tool-use id) — task 3.4 filters the
     * tool trace on this field; this task only preserves null-vs-present
     * distinctly, never collapsing an absent field to a sentinel.
     *
     * @param sessionId the CLI's session id for this round; never blank
     * @param parentToolUseId the enclosing top-level tool-use id when this event
     *     belongs to a nested subagent round, or {@code null} for a top-level
     *     event
     * @param model the model that produced this message, or {@code null} when
     *     the message carried none
     * @param content the message's content blocks in wire order; defensively
     *     copied and unmodifiable, possibly empty
     */
    record AssistantEvent(
            String sessionId,
            @Nullable String parentToolUseId,
            @Nullable String model,
            List<ContentBlock> content) implements AgentEvent {

        public AssistantEvent {
            sessionId = requireNonBlank(sessionId, "sessionId");
            content = List.copyOf(content);
        }
    }

    /**
     * A {@code user} message event: in stream-json this always carries the tool
     * results answering the prior {@code assistant} event's tool-use blocks
     * (this project's stage prompts never inject a genuine human turn mid-round
     * — the CLI's {@code user} events are exclusively the tool-result channel).
     * {@code parentToolUseId} mirrors {@link AssistantEvent#parentToolUseId()}
     * for the same top-level-vs-nested distinction.
     *
     * @param sessionId the CLI's session id for this round; never blank
     * @param parentToolUseId the enclosing top-level tool-use id when this event
     *     belongs to a nested subagent round, or {@code null} for a top-level
     *     event
     * @param content the message's content blocks in wire order; defensively
     *     copied and unmodifiable, possibly empty
     */
    record UserEvent(String sessionId, @Nullable String parentToolUseId, List<ContentBlock> content)
            implements AgentEvent {

        public UserEvent {
            sessionId = requireNonBlank(sessionId, "sessionId");
            content = List.copyOf(content);
        }
    }

    /**
     * The round-closing {@code result} event: essential per FR4/D3 — an
     * unparseable or missing one is an infrastructure failure of the round (task
     * 3.2's concern, not this one). {@code usage} and {@code modelUsage} are
     * carried as raw wire data (JSON-derived {@code Map}/{@code Number} values,
     * not yet coerced into {@link com.github.oinsio.gnomish.domain.engine.TokenUsage});
     * task 3.3 interprets them numerically and resolves the {@code modelUsage}
     * absent-vs-present fallback to the init event's main model. {@code
     * modelUsage} is {@code null}, distinct from an empty map, exactly when the
     * wire event omitted the key — collapsing the two would erase the very
     * signal task 3.3's fallback depends on.
     *
     * @param sessionId the CLI's session id for this round; never blank
     * @param result the agent's final result text; never null (the CLI may
     *     report an empty string)
     * @param usage the event's raw {@code usage} object (token counts under
     *     CLI-native keys), or {@code null} if the event carried none
     * @param modelUsage the event's raw {@code modelUsage} object (per-model
     *     token counts under CLI-native keys), or {@code null} if the wire event
     *     omitted the key entirely (older CLIs); defensively copied and
     *     unmodifiable when present
     */
    record ResultEvent(
            String sessionId,
            String result,
            @Nullable Map<String, Object> usage,
            @Nullable Map<String, Object> modelUsage)
            implements AgentEvent {

        public ResultEvent {
            sessionId = requireNonBlank(sessionId, "sessionId");
            result = requireNonNull(result, "ResultEvent.result");
            usage = usage == null ? null : Map.copyOf(usage);
            modelUsage = modelUsage == null ? null : Map.copyOf(modelUsage);
        }
    }

    /**
     * Fails fast on a blank {@code sessionId} shared by every variant: an event
     * with no session identity cannot be correlated to a round. Kept as an
     * explicit static method rather than inline in a compact constructor: PIT's
     * record filter suppresses all mutations inside a record's canonical
     * constructor, which would silently exempt this validation from the 100%
     * mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("AgentEvent." + component + " must not be blank");
        }
        return value;
    }

    /**
     * Fails fast on a null {@code result}: the CLI may report an empty string
     * but never omits the field entirely — a null here signals a mapping bug,
     * not wire data. Same explicit-static-method rationale as {@link
     * #requireNonBlank}.
     */
    private static String requireNonNull(String value, String component) {
        if (value == null) {
            throw new NullPointerException("AgentEvent." + component + " must not be null");
        }
        return value;
    }
}
