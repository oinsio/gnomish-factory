package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.adapter.agent.program.ProgressLines;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.nio.charset.StandardCharsets;
import java.time.InstantSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What an agent round does with each stdout line as it arrives (FR1, FR2, FR6, FR8 of
 * extract-agent-round-runner): the {@link ProgressLines.Sink} of the round's live reader. A line that
 * is blank is skipped; any other line — cut to the line cap when it was longer — goes to the dialect's
 * {@link AgentDialect#parse} against the round's history, its events are kept within the retained cap
 * ({@link RetainedEvents}) with the instant the line was read, and its vendor-neutral progress is handed
 * to the round's listener there and then, while the process runs.
 *
 * <p>What the caps cost the round is said once, when the round is judged ({@link #reportCaps}): one
 * WARN naming how many lines were cut and how many events were dropped, and nothing when neither
 * happened. The round still completes on what it kept.
 *
 * <p>The sink runs on the reader's thread; the round's thread reads {@link #events()} and reports
 * only after joining the reader.
 *
 * <p>Implements FR1, FR2, FR6, FR8, NFR-R1 of extract-agent-round-runner.
 *
 * @param <E> the dialect's own event type
 */
final class DialectLines<E> implements ProgressLines.Sink {

    private static final Logger log = LoggerFactory.getLogger(DialectLines.class);

    private final AgentDialect<E> dialect;
    private final AgentProgressListener listener;
    private final InstantSource clock;
    private final StdoutCaps caps;
    private final RetainedEvents<E> retained;
    private long cutLines;

    /**
     * @param dialect the round's dialect; never null
     * @param listener the round's progress listener, which must not throw; never null
     * @param clock the source each line's arrival instant is read from; never null
     * @param caps the round's stdout caps; never null
     */
    DialectLines(AgentDialect<E> dialect, AgentProgressListener listener, InstantSource clock, StdoutCaps caps) {
        this.dialect = dialect;
        this.listener = listener;
        this.clock = clock;
        this.caps = caps;
        this.retained = new RetainedEvents<>(caps.retainedBytes());
    }

    @Override
    public void line(byte[] line, boolean cut) {
        String text = new String(line, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return;
        }
        if (cut) {
            cutLines++;
        }
        ParsedLine<E> parsed = dialect.parse(text, retained.view());
        retained.add(parsed.events(), clock.instant(), line.length);
        parsed.progress().forEach(listener::onProgress);
    }

    /** The events the round kept, for the dialect's judgement after the reader was joined. */
    ParsedEvents<E> events() {
        return retained.view();
    }

    /**
     * Says what the caps cost the round, once, after the reader was joined: nothing when no line was
     * cut and no event dropped.
     *
     * @param executor the executor's name; never blank
     */
    void reportCaps(String executor) {
        if (cutLines == 0 && retained.dropped() == 0) {
            return;
        }
        log.warn(
                OperatorEvent.AGENT_STDOUT_CAPPED.head()
                        + "agent executor '{}' wrote past its stdout caps: {} line(s) cut at {} bytes, {} event(s)"
                        + " dropped past {} bytes kept; the round is judged by what was kept",
                executor,
                cutLines,
                caps.lineBytes(),
                retained.dropped(),
                caps.retainedBytes());
    }
}
