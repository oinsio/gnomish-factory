package com.github.oinsio.gnomish.adapter.agent;

/**
 * The adapter-internal SPI through which {@link StreamJsonParser} reports live
 * progress on a round in flight — an SLF4J renderer, a status enricher — as a
 * sibling of the domain's {@link
 * com.github.oinsio.gnomish.domain.engine.port.EngineEventListener}, not an
 * extension of it: executor-internal progress (per-tool, mid-round) is this
 * adapter's detail, while the engine's own event stream is per-round (design
 * D10's rejected alternative).
 *
 * <p>Carries {@link
 * com.github.oinsio.gnomish.domain.engine.port.EngineEventListener}'s contract
 * verbatim (design D10): delivery is <em>synchronous</em>, inline on the parse
 * loop's critical path, so a slow listener slows the round it observes; an
 * implementation must return promptly, offloading any real work internally. A
 * listener that throws does <em>not</em> interrupt parsing: {@link
 * StreamJsonParser} catches the exception, logs it, and swallows it — this call
 * is observability, never an effect, so its failure is never one either.
 *
 * <p>Implements FR7, D10 of add-agent-executor.
 */
public interface AgentProgressListener {

    /**
     * Delivers one {@link AgentProgressEvent} to the observer, synchronously on
     * the parse loop's critical path. The implementation must return promptly,
     * offloading any real work internally; it may throw, in which case the
     * caller logs and swallows the exception rather than interrupting parsing
     * (this call is observability, never an effect).
     *
     * <p>Implements FR7, D10 of add-agent-executor.
     *
     * @param event the progress event that just occurred; never null
     */
    void onProgress(AgentProgressEvent event);
}
