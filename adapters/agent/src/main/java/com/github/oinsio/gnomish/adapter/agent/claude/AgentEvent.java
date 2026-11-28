package com.github.oinsio.gnomish.adapter.agent.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * <p>The text fields a gnome's own agent chose — the session id, the model id it
 * reports, and the round's final result — are {@link UntrustedText}, minted by
 * {@link StreamJsonEventMapper} as the line is recognized (design D3, D4 of
 * type-untrusted-text). The wire tokens beside them stay plain: {@code subtype} is a
 * protocol constant the parser switches on, and {@code parentToolUseId} is an id the
 * trace correlates by, never text a reader sees.
 *
 * <p>Implements FR4, D3 of add-agent-executor; FR1, FR7 of type-untrusted-text.
 */
public sealed interface AgentEvent {

    /**
     * The {@code system}/{@code init} event that opens a round: the resolved
     * main model, used as the token-mapping fallback (task 3.3, FR5) and the
     * {@code RoundStarted} progress payload (task 3.5, FR7), and the session id,
     * which is the round's continuation key (design D4 of
     * extract-agent-round-runner) and never a progress field (FR6 there).
     *
     * @param sessionId the CLI's session id for this round; never blank
     * @param model the round's main model id; never blank
     */
    record InitEvent(UntrustedText sessionId, UntrustedText model) implements AgentEvent {

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
            UntrustedText sessionId,
            @Nullable String parentToolUseId,
            @Nullable UntrustedText model,
            List<ContentBlock> content)
            implements AgentEvent {

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
    record UserEvent(UntrustedText sessionId, @Nullable String parentToolUseId, List<ContentBlock> content)
            implements AgentEvent {

        public UserEvent {
            sessionId = requireNonBlank(sessionId, "sessionId");
            content = List.copyOf(content);
        }
    }

    /**
     * The round-closing {@code result} event: essential per FR4/D3 — a missing one is an
     * infrastructure failure of the round. {@code usage} and {@code modelUsage} are carried as raw
     * wire data, interpreted by {@link TokenUsageMapper}; {@code modelUsage} is {@code null}, distinct
     * from an empty map, exactly when the wire event omitted the key (older CLIs), the signal the
     * mapper's fallback to the init event's model depends on.
     *
     * @param sessionId the CLI's session id for this round; never blank
     * @param subtype the event's {@code subtype} verbatim, or {@code null} if it carried none
     * @param result the agent's final result text; never null (the CLI may report an empty string)
     * @param usage the raw {@code usage} object, or {@code null} if the event carried none
     * @param modelUsage the raw {@code modelUsage} object, or {@code null} if the key was omitted;
     *     defensively copied and unmodifiable when present
     * @param structuredOutput the {@code structured_output} the CLI validated against the {@code
     *     --json-schema} it was handed, or {@code null} when it reported none; the agent's JSON,
     *     judged and minted only by the dialect's answer step (FR3 of extract-agent-round-runner)
     */
    record ResultEvent(
            UntrustedText sessionId,
            @Nullable String subtype,
            UntrustedText result,
            @Nullable Map<String, Object> usage,
            @Nullable Map<String, Object> modelUsage,
            @Nullable JsonNode structuredOutput)
            implements AgentEvent {

        public ResultEvent {
            sessionId = requireNonBlank(sessionId, "sessionId");
            result = requireNonNull(result, "ResultEvent.result");
            usage = copyOfPresent(usage);
            modelUsage = copyOfPresent(modelUsage);
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
    private static UntrustedText requireNonBlank(UntrustedText value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("AgentEvent." + component + " must not be blank");
        }
        return value;
    }

    /**
     * Defensively copies a raw wire object into an unmodifiable map, dropping the keys the CLI
     * reported as JSON {@code null} ({@code "fallback_credit": null} in Claude Code 2.1.x's
     * {@code usage}). {@link Map#copyOf} rejects null values, so copying such a line verbatim threw
     * on every round and failed it as an executor error. A null-valued key and an absent one read
     * the same to every consumer ({@code Map.get} returns null for both), so nothing is lost. A
     * null map stays null — the absent-vs-present signal {@link TokenUsageMapper} falls back on.
     * Same explicit-static-method rationale as {@link #requireNonBlank}.
     */
    private static @Nullable Map<String, Object> copyOfPresent(@Nullable Map<String, Object> wire) {
        if (wire == null) {
            return null;
        }
        Map<String, Object> present = new LinkedHashMap<>();
        wire.forEach((key, value) -> {
            if (value != null) {
                present.put(key, value);
            }
        });
        return Collections.unmodifiableMap(present);
    }

    /**
     * Fails fast on a null {@code result}: the CLI may report an empty string
     * but never omits the field entirely — a null here signals a mapping bug,
     * not wire data. Same explicit-static-method rationale as {@link
     * #requireNonBlank}.
     */
    private static UntrustedText requireNonNull(UntrustedText value, String component) {
        if (value == null) {
            throw new NullPointerException("AgentEvent." + component + " must not be null");
        }
        return value;
    }
}
