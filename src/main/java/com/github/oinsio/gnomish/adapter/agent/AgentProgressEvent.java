package com.github.oinsio.gnomish.adapter.agent;

/**
 * A live progress signal emitted by {@link StreamJsonParser}'s parse loop as it
 * recognizes an event on a round's stream-json output, delivered to an {@link
 * AgentProgressListener} (design D10). Unlike {@link AgentEvent} — the durable
 * per-line wire model later folded into {@link ExecutorUsage} / {@link
 * ToolTraceBuilder} — a progress event exists only for the moment of observation:
 * it carries the minimum a live subscriber (an SLF4J renderer, a status enricher)
 * needs to report a round in flight, not the full wire payload.
 *
 * <p>Three variants cover FR7's three live-progress facts: a round starting
 * (model, session id), a top-level tool call starting, and a round's closing
 * summary (the {@code result} event's final-message text, per design D9(c) —
 * "the final message becomes the round summary ... it rides the {@code
 * RoundFinished} progress event and the log, not the domain"). Judge rounds feed
 * the same event stream as executor rounds — this type does not distinguish the
 * two; that distinction belongs to which listeners a caller wires (FR7).
 *
 * <p>Implements FR7, D9, D10 of add-agent-executor.
 */
public sealed interface AgentProgressEvent {

    /**
     * A round has started: emitted when {@link StreamJsonParser} recognizes the
     * round's {@code system}/{@code init} event.
     *
     * @param model the round's main model id, verbatim from {@link
     *     AgentEvent.InitEvent#model()}; never blank
     * @param sessionId the CLI's session id for this round, verbatim from {@link
     *     AgentEvent.InitEvent#sessionId()}; never blank
     */
    record RoundStarted(String model, String sessionId) implements AgentProgressEvent {

        public RoundStarted {
            model = requireNonBlank(model, "model");
            sessionId = requireNonBlank(sessionId, "sessionId");
        }
    }

    /**
     * A top-level tool call has started: emitted once per top-level {@link
     * ContentBlock.ToolUse} block, in the order encountered, when {@link
     * StreamJsonParser} recognizes an {@code assistant} event whose {@link
     * AgentEvent.AssistantEvent#parentToolUseId()} is {@code null} — the same
     * top-level notion {@link ToolTraceBuilder} applies post-hoc, checked inline
     * here as each line is parsed (FR7: "top-level tool started"). A nested
     * subagent tool call (non-null {@code parentToolUseId}) never produces this
     * event.
     *
     * @param name the tool's name, verbatim from {@link
     *     ContentBlock.ToolUse#name()}; never blank
     */
    record ToolStarted(String name) implements AgentProgressEvent {

        public ToolStarted {
            name = requireNonBlank(name, "name");
        }
    }

    /**
     * A round has finished: emitted when {@link StreamJsonParser} recognizes the
     * round's {@code result} event. {@code summary} is {@link
     * AgentEvent.ResultEvent#result()} verbatim (design D9(c)) — the same text
     * the domain never sees as decision data, only as a log line and this event.
     *
     * <p>Carries no {@code sessionId}: unlike {@link RoundStarted} / {@link
     * ToolStarted}, which report facts about an event mid-stream, a listener
     * receives {@code RoundStarted} and {@code RoundFinished} for the same round
     * as a strictly ordered pair on one subscriber instance scoped to that round
     * (mirroring how {@link StreamJsonParser#parse} itself is called once per
     * round) — correlation by field is redundant with correlation by call order,
     * and the result event's session id carries no information {@code
     * RoundStarted} did not already report.
     *
     * @param summary the round's final-message text, verbatim from {@link
     *     AgentEvent.ResultEvent#result()}; never null, possibly empty
     */
    record RoundFinished(String summary) implements AgentProgressEvent {

        public RoundFinished {
            requireNonNull(summary, "summary");
        }
    }

    /**
     * Fails fast on a blank component shared by {@link RoundStarted} and {@link
     * ToolStarted}. Kept as an explicit static method rather than inline in a
     * compact constructor: PIT's record filter suppresses all mutations inside a
     * record's canonical constructor, which would silently exempt this
     * validation from the 100% mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("AgentProgressEvent." + component + " must not be blank");
        }
        return value;
    }

    /**
     * Fails fast on a null {@code summary}: the CLI may report an empty string
     * but never omits the field entirely. Same explicit-static-method rationale
     * as {@link #requireNonBlank}.
     */
    private static void requireNonNull(String value, String component) {
        if (value == null) {
            throw new NullPointerException("AgentProgressEvent." + component + " must not be null");
        }
    }
}
