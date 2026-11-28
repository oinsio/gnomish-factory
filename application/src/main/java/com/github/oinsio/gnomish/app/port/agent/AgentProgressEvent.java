package com.github.oinsio.gnomish.app.port.agent;

import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Map;

/**
 * A live progress signal a round's dialect recognizes on the agent CLI's output, delivered to an
 * {@link AgentProgressListener} (design D10 of add-agent-executor). Unlike the dialect's own per-line
 * wire model — later folded into {@link ExecutorUsage} and the tool trace — a progress event exists
 * only for the moment of observation: it carries the minimum a live subscriber (an SLF4J renderer, a
 * mid-round harvest poll) needs to report a round in flight, not the full wire payload.
 *
 * <p>Three variants cover the three live-progress facts: a round starting (model), a top-level tool
 * call starting (tool name), and a round finishing (token usage and the final-message text, per
 * design D9(c) of add-agent-executor — "the final message becomes the round summary ... it rides the
 * {@code RoundFinished} progress event and the log, not the domain"). Judge rounds feed the same
 * event stream as executor rounds — this type does not distinguish the two; that distinction
 * belongs to which listeners a caller wires.
 *
 * <p>The events are vendor-neutral (FR6 of extract-agent-round-runner): model, tool name, final
 * summary and usage, and nothing a particular CLI's wire format names. The session id is not here —
 * it travels as the continuation key in the round's result (design D4 of that change) — and neither
 * is a result subtype: how a round ended is the dialect's status mapping, not a progress fact.
 *
 * <p>Implements FR7, D9, D10 of add-agent-executor; FR6 of extract-agent-round-runner.
 */
public sealed interface AgentProgressEvent {

    /**
     * A round has started: emitted when the dialect recognizes the event that opens the round.
     *
     * @param model the round's main model id, verbatim as the CLI reported it; never blank
     */
    record RoundStarted(UntrustedText model) implements AgentProgressEvent {

        public RoundStarted {
            requireNonBlank(model, "model");
        }
    }

    /**
     * A top-level tool call has started: emitted once per top-level {@code
     * adapter.agent.claude.ContentBlock.ToolUse} block, in the order encountered, when {@code
     * adapter.agent.claude.StreamJsonParser} recognizes an {@code assistant} event whose {@code
     * adapter.agent.claude.AgentEvent.AssistantEvent#parentToolUseId()} is {@code null} — the same
     * top-level notion {@code adapter.agent.claude.ToolTraceBuilder} applies post-hoc, checked inline
     * here as each line is parsed (FR7: "top-level tool started"). A nested
     * subagent tool call (non-null {@code parentToolUseId}) never produces this
     * event.
     *
     * @param name the tool's name, verbatim from {@code
     *     adapter.agent.claude.ContentBlock.ToolUse#name()} and minted as agent text where it is read
     *     off the stream; never blank
     */
    record ToolStarted(UntrustedText name) implements AgentProgressEvent {

        public ToolStarted {
            requireNonBlank(name, "name");
        }
    }

    /**
     * A round has finished: emitted when the dialect recognizes the event that closes the round,
     * carrying its token usage and its final-message summary — the same text the domain never sees
     * as decision data, only as a log line and this event (design D9(c) of add-agent-executor).
     * {@code tokensByModel} is derived the same way the round's usage is: best-effort, empty when
     * the wire reported nothing interpretable.
     *
     * <p>Carries no correlation id: a listener receives {@code RoundStarted} and {@code
     * RoundFinished} for the same round as a strictly ordered pair on one subscriber scoped to that
     * round, so correlation by call order is all a listener needs.
     *
     * @param tokensByModel the round's token usage keyed by resolved model id; defensively copied,
     *     unmodifiable, empty when unreported
     * @param summary the round's final-message text, verbatim; never null, possibly empty
     */
    record RoundFinished(Map<String, TokenUsage> tokensByModel, UntrustedText summary) implements AgentProgressEvent {

        public RoundFinished {
            tokensByModel = Map.copyOf(tokensByModel);
            requireNonNull(summary);
        }
    }

    /**
     * Fails fast on a blank component shared by {@link RoundStarted} and {@link ToolStarted} —
     * every one of them is text the agent itself chose, so each travels as the carrier it was
     * minted in (design D4 of type-untrusted-text) and a listener renders it through an exit
     * rather than receiving a string this port already flattened. Kept as an explicit static
     * method rather than inline in a compact constructor: PIT's record filter suppresses all
     * mutations inside a record's canonical constructor, which would silently exempt this
     * validation from the 100% mutation gate.
     */
    private static void requireNonBlank(UntrustedText value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("AgentProgressEvent." + component + " must not be blank");
        }
    }

    /**
     * Fails fast on a null {@code summary}: the CLI may report an empty string
     * but never omits the field entirely. Same explicit-static-method rationale
     * as {@link #requireNonBlank}. Takes no {@code component} parameter — unlike
     * {@link #requireNonBlank}, this check has exactly one caller.
     */
    private static void requireNonNull(UntrustedText value) {
        if (value == null) {
            throw new NullPointerException("AgentProgressEvent.summary must not be null");
        }
    }
}
