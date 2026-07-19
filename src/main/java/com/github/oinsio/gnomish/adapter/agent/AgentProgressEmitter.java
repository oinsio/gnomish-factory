package com.github.oinsio.gnomish.adapter.agent;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches the {@link AgentProgressEvent}(s) a recognized {@link AgentEvent}
 * implies to an injected {@link AgentProgressListener} (design D10, FR7).
 * Extracted from {@link StreamJsonParser} (design D3) to separate the parse
 * loop's own concern — tolerant reading, JSON decoding, read-time {@code
 * Clock} stamping (NFR-O3) — from progress fan-out, a detachable half with no
 * need for the {@code Clock}; the DEBUG raw-event log stays in {@link
 * StreamJsonParser}, which owns the loop that reads each line.
 *
 * <p>Implements FR1, FR3, NFR-O1 of fix-oversized-adapters.
 */
final class AgentProgressEmitter {

    private static final Logger log = LoggerFactory.getLogger(AgentProgressEmitter.class);

    private final AgentProgressListener progressListener;
    private final TokenUsageMapper tokenUsageMapper;

    /**
     * @param progressListener the live-progress subscriber (design D10); never
     *     null — pass a fan-out implementation to reach several subscribers, or a
     *     no-op ({@code event -> {}}) to reach none
     * @param tokenUsageMapper derives {@code tokensByModel} for {@code
     *     RoundFinished} from an {@link AgentEvent.ResultEvent}; never null
     */
    AgentProgressEmitter(AgentProgressListener progressListener, TokenUsageMapper tokenUsageMapper) {
        this.progressListener = progressListener;
        this.tokenUsageMapper = tokenUsageMapper;
    }

    /**
     * Dispatches the {@link AgentProgressEvent}(s) implied by {@code event}
     * (design D10, FR7): {@code RoundStarted} for an {@link
     * AgentEvent.InitEvent}, one {@code ToolStarted} per top-level {@link
     * ContentBlock.ToolUse} block for an {@link AgentEvent.AssistantEvent}
     * whose {@code parentToolUseId} is {@code null}, {@code RoundFinished} for
     * an {@link AgentEvent.ResultEvent} — its {@code tokensByModel} derived by
     * {@link TokenUsageMapper} the same way {@link
     * AgentRoundResultExtractor}'s telemetry is, using {@code roundInit} for
     * the flat-{@code usage} fallback's model key. A {@link
     * AgentEvent.UserEvent} implies no progress event.
     *
     * @param roundInit the round's {@link AgentEvent.InitEvent} recognized so
     *     far by the caller's parse loop, or {@code null} if none has been
     *     recognized yet
     */
    void emit(AgentEvent event, AgentEvent.@Nullable InitEvent roundInit) {
        switch (event) {
            case AgentEvent.InitEvent init ->
                deliver(new AgentProgressEvent.RoundStarted(init.model(), init.sessionId()));
            case AgentEvent.AssistantEvent assistant -> {
                if (assistant.parentToolUseId() == null) {
                    for (ContentBlock block : assistant.content()) {
                        if (block instanceof ContentBlock.ToolUse toolUse) {
                            deliver(new AgentProgressEvent.ToolStarted(toolUse.name()));
                        }
                    }
                }
            }
            case AgentEvent.ResultEvent result -> {
                var tokensByModel = tokenUsageMapper.toTokensByModel(result, roundInit);
                deliver(new AgentProgressEvent.RoundFinished(result.subtype(), tokensByModel, result.result()));
            }
            case AgentEvent.UserEvent ignored -> {
                // tool results carry no progress signal of their own (FR7)
            }
        }
    }

    /**
     * Delivers {@code event} to {@link #progressListener}, swallowing and
     * logging any {@link RuntimeException} it throws so a broken listener never
     * interrupts parsing (design D10) — mirroring how {@code Events.emit}
     * shields the engine's own event delivery.
     */
    private void deliver(AgentProgressEvent event) {
        try {
            progressListener.onProgress(event);
        } catch (RuntimeException ex) {
            log.warn("agent progress listener threw for {}", event, ex);
        }
    }
}
