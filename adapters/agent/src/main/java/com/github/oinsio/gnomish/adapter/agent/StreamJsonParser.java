package com.github.oinsio.gnomish.adapter.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.logtext.LogText;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tolerant parse loop over one round's stream-json output (design D3): reads
 * {@code --output-format stream-json --verbose} line by line, parses each line
 * as JSON, and dispatches it to a known {@link AgentEvent} variant when {@code
 * type} (and {@code subtype} where relevant) matches. A non-JSON line, an
 * unknown {@code type}, or a line missing a field a known type requires is
 * silently skipped — parsing continues to the next line — because the format is
 * semi-documented and drifts between CLI versions (FR4); this loop never
 * throws on malformed input.
 *
 * <p>Skips are logged at DEBUG (not WARN): unlike the decision-file/verdict
 * tolerant reads (NFR-O2), an unknown stream-json line is expected, routine
 * traffic (subagent events this CLI version does not model, format drift) —
 * escalating every occurrence to WARN would make normal rounds noisy.
 *
 * <p>Stream-json lines carry no timestamps, so each recognized event is stamped
 * with the injected {@link Clock}'s reading taken at the moment its line is
 * read off the process's stdout (design D3, FR6, NFR-O3) — {@link
 * ToolTraceBuilder} derives tool-call durations from these read-time instants.
 * The {@link Clock} is read live inside the loop, not reconstructed afterward:
 * by the time a full event list is available, the real timing information is
 * already lost.
 *
 * <p>The same loop is where live progress is emitted (design D10, FR7): as an
 * {@code init}, top-level {@code tool_use}, or {@code result} line is recognized,
 * the matching {@link AgentProgressEvent} is delivered to the injected {@link
 * AgentProgressListener} before the loop reads the next line — not scanned from
 * the returned list afterward, since the whole point of live progress is that a
 * subscriber (a console spinner, a status enricher) sees it while the round is
 * still running. A caller with several subscribers composes them the same way
 * {@code EnginePorts} composes {@link
 * com.github.oinsio.gnomish.domain.engine.port.EngineEventListener}s — via a
 * fan-out implementation of this one-listener slot — rather than this class
 * accepting a list itself. The dispatch itself is delegated to {@link
 * AgentProgressEmitter} (design D3, fix-oversized-adapters): this class keeps
 * the tolerant read/parse loop and the read-time {@link Clock} stamping, and
 * calls the emitter inline before reading the next line to preserve this
 * live-before-next-line ordering.
 *
 * <p>Every recognized raw {@link AgentEvent} is also logged at DEBUG (task
 * 8.1), one line per event, independent of and in addition to the derived
 * {@link AgentProgressEvent} the emitter delivers: the raw line is the
 * full-fidelity record for a deep debugging session, while the progress event
 * is the coarse, always-INFO summary a renderer such as {@link
 * LoggingAgentProgressListener} surfaces (NFR-O1, UX1).
 *
 * <p>Implements FR4, FR6, FR7, NFR-O1, UX1, NFR-O3, D3, D10 of add-agent-executor.
 */
public final class StreamJsonParser {

    private static final Logger log = LoggerFactory.getLogger(StreamJsonParser.class);

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Clock clock;
    private final AgentProgressEmitter progressEmitter;

    /**
     * Equivalent to {@link #StreamJsonParser(Clock, AgentProgressListener)} with
     * a no-op listener, for callers that do not need live progress (existing
     * telemetry-only call sites, tests focused on the parsed event list).
     *
     * @param clock the read-time source stamped onto each recognized event; never
     *     null (production wiring uses {@link
     *     com.github.oinsio.gnomish.domain.engine.time.SystemClock}, tests a
     *     controllable fake)
     */
    public StreamJsonParser(Clock clock) {
        this(clock, _ -> {});
    }

    /**
     * @param clock the read-time source stamped onto each recognized event; never
     *     null (production wiring uses {@link
     *     com.github.oinsio.gnomish.domain.engine.time.SystemClock}, tests a
     *     controllable fake)
     * @param progressListener the live-progress subscriber (design D10); never
     *     null — pass a fan-out implementation to reach several subscribers, or a
     *     no-op ({@code event -> {}}) to reach none
     */
    public StreamJsonParser(Clock clock, AgentProgressListener progressListener) {
        this.clock = clock;
        this.progressEmitter = new AgentProgressEmitter(progressListener, new TokenUsageMapper());
    }

    /**
     * Parses every line of {@code reader} into the {@link TimestampedEvent}s it
     * recognizes, in wire order, skipping everything it does not (FR4). Reads
     * to exhaustion (end of stream) rather than stopping at a first bad line —
     * a single malformed or unrecognized line carries no information about the
     * lines that follow. Each recognized event is stamped with {@link
     * Clock#now()} read at the moment its line is consumed (FR6, NFR-O3).
     *
     * @param reader the round's stdout, line-buffered; never null; not closed
     *     by this method — the caller owns its lifecycle
     * @return the recognized events, each paired with its read-time instant, in
     *     wire order; never null, possibly empty
     * @throws UncheckedIOException if the underlying reader fails (a genuine
     *     I/O error, not a parse error — those are swallowed per FR4)
     */
    public List<TimestampedEvent> parse(BufferedReader reader) {
        List<TimestampedEvent> events = new ArrayList<>();
        parseInto(reader, events);
        return events;
    }

    /**
     * The same loop as {@link #parse}, appending each recognized event to
     * {@code sink} as its line is read rather than returning a list at the end
     * (FR1 of fix-round-stdout-drain). {@link StreamDrain} runs this on its own
     * thread with a thread-safe {@code sink}, so the events parsed so far are
     * published as they arrive instead of only on a clean end-of-stream — which
     * is also what leaves a partially-read stream's events in place when the
     * read fails midway.
     *
     * @param reader the round's stdout, line-buffered; never null; not closed by
     *     this method — the caller owns its lifecycle
     * @param sink the collection each recognized event is appended to, in wire
     *     order; never null, must tolerate the calling thread
     * @throws UncheckedIOException if the underlying reader fails (a genuine
     *     I/O error, not a parse error — those are swallowed per FR4)
     */
    public void parseInto(BufferedReader reader, List<TimestampedEvent> sink) {
        AgentEvent.InitEvent[] initEvent = new AgentEvent.InitEvent[1];
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                var readAt = clock.now();
                parseLine(line).ifPresent(event -> {
                    // FR6: a recognized event still carries the agent's own words (assistant
                    // text, tool arguments, the result summary), so the rendered record is
                    // neutralized and capped before it becomes a line.
                    log.debug("raw agent event: {}", LogText.forLog(String.valueOf(event)));
                    sink.add(new TimestampedEvent(event, readAt));
                    if (event instanceof AgentEvent.InitEvent init) {
                        initEvent[0] = init;
                    }
                    progressEmitter.emit(event, initEvent[0]);
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private java.util.Optional<AgentEvent> parseLine(String line) {
        if (line.isBlank()) {
            return java.util.Optional.empty();
        }
        StreamJsonLine wire;
        try {
            wire = MAPPER.readValue(line, StreamJsonLine.class);
        } catch (JsonProcessingException e) {
            log.debug("stream-json: skipping non-JSON or malformed line", e);
            return java.util.Optional.empty();
        }
        return StreamJsonEventMapper.toEvent(wire);
    }
}
