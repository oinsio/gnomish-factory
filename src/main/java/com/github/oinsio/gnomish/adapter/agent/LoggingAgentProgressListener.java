package com.github.oinsio.gnomish.adapter.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link AgentProgressListener} adapter that logs one structured INFO line
 * per {@link AgentProgressEvent} to the rolling file appender (task 8.1, design
 * D10): round start (model, session id), each top-level tool started, and round
 * finish (subtype, token summary, summary) — the live, executor-internal
 * counterpart to {@link
 * com.github.oinsio.gnomish.status.LoggingEventListener}'s per-round engine
 * lines. Every line is logged through SLF4J with parameterized arguments —
 * never string concatenation — matching that class's established idiom.
 *
 * <p>The {@code taskId}/{@code stage}/{@code attempt} MDC keys are already set
 * on the calling thread by the time this listener runs (task 8.2, {@link
 * com.github.oinsio.gnomish.status.MdcEventListener}) — an attempt's executor
 * round always runs inside that MDC scope — so this listener does not set MDC
 * itself; it only logs.
 *
 * <p>One instance is shared by both the executor and judge CLI adapters (app
 * assembly, task 9.4, out of scope here): a judge round's rounds flow through
 * the same {@link StreamJsonParser} live-progress SPI as an executor round and
 * so land on this same renderer, indistinguishable in shape from an executor
 * round's lines — the surrounding MDC (stage/attempt) is what tells them apart
 * in the log file.
 *
 * <p>Implements FR7, NFR-O1, UX1, D10 of add-agent-executor.
 */
public final class LoggingAgentProgressListener implements AgentProgressListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingAgentProgressListener.class);

    /**
     * Logs one structured INFO line for {@code event}, with content specific to
     * its kind, by an exhaustive switch over the sealed {@link
     * AgentProgressEvent} variants — no {@code default} arm, so a new variant
     * fails to compile here until its log line is added.
     *
     * <p>Implements FR7, NFR-O1, UX1, D10 of add-agent-executor.
     *
     * @param event the progress event that just occurred; never null
     */
    @Override
    public void onProgress(AgentProgressEvent event) {
        switch (event) {
            case AgentProgressEvent.RoundStarted started ->
                log.info("round started: model={}, sessionId={}", started.model(), started.sessionId());
            case AgentProgressEvent.ToolStarted started -> log.info("tool started: {}", started.name());
            case AgentProgressEvent.RoundFinished finished ->
                log.info(
                        "round finished: subtype={}, tokensByModel={}, summary={}",
                        finished.subtype(),
                        finished.tokensByModel(),
                        finished.summary());
        }
    }
}
