package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.EngineEvent;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import org.slf4j.MDC;

/**
 * The {@link EngineEventListener} adapter that maintains the {@code stage}/{@code attempt} SLF4J
 * MDC keys the logging pattern in {@code logback-spring.xml} (task 8.1) references, so every log
 * line emitted on the engine thread while an attempt is in flight is tagged with its correlation
 * key (design D9). Listeners run synchronously on the engine thread (design D7 of
 * add-stage-engine), so setting the MDC here — rather than in the domain — keeps the domain
 * MDC-free while still tagging every log line the engine's own execution triggers (NFR-O1).
 *
 * <p>{@code taskId} is deliberately out of this listener's scope: unlike {@code stage}/{@code
 * attempt}, it does not change during a run, so the runner sets it once, directly, before the
 * engine loop starts ({@link com.github.oinsio.gnomish.app.ManualRunRunner}) rather than via a
 * per-event listener (design D9, task 8.2).
 *
 * <p>Every {@link EngineEvent} variant carrying an {@link AttemptKey} — {@code AttemptStarted},
 * {@code ExecutionFinished}, {@code CheckStarted}, {@code CheckFinished}, {@code AttemptFinished}
 * — sets both keys from that key. {@code RunStarted} carries a {@link Position} rather than an
 * {@code AttemptKey}; when the resolved position is {@link Position.AtStage}, this listener sets
 * {@code stage} from it so the MDC already names the stage before the first attempt starts (e.g.
 * a resume that logs before any attempt event fires) — {@code attempt} is left unset there since
 * {@code RunStarted} carries no attempt number ({@code attemptsUsed} counts burned attempts, not
 * the next attempt's index). {@code TaskFinished} clears both keys, preventing them from leaking
 * into any logging that happens after the run ends (task 8.2's explicit "cleared on {@code
 * TaskFinished}").
 *
 * <p>Every branch here is a plain {@link MDC} map mutation with no I/O, so this class naturally
 * satisfies the port's "never throw past {@code onEvent}" contract without defensive exception
 * handling — the same reasoning {@link StatusEventListener} documents for its own plain field
 * updates.
 *
 * <p>Implements NFR-O1, D9 of add-manual-run.
 */
public final class MdcEventListener implements EngineEventListener {

    static final String STAGE_KEY = "stage";
    static final String ATTEMPT_KEY = "attempt";

    /**
     * Updates the {@code stage}/{@code attempt} MDC keys per the mapping documented on the class.
     *
     * <p>Implements NFR-O1, D9 of add-manual-run.
     *
     * @param event the event that just occurred; never null
     */
    @Override
    public void onEvent(EngineEvent event) {
        switch (event) {
            case EngineEvent.AttemptStarted started -> put(started.key());
            case EngineEvent.ExecutionFinished finished -> put(finished.key());
            case EngineEvent.CheckStarted started -> put(started.key());
            case EngineEvent.CheckFinished finished -> put(finished.key());
            case EngineEvent.AttemptFinished finished -> put(finished.key());
            case EngineEvent.RunStarted started -> onRunStarted(started.position());
            case EngineEvent.TaskFinished ignored -> clear();
        }
    }

    private void onRunStarted(Position position) {
        if (position instanceof Position.AtStage atStage) {
            MDC.put(STAGE_KEY, atStage.name());
        }
    }

    private void put(AttemptKey key) {
        MDC.put(STAGE_KEY, key.stage());
        MDC.put(ATTEMPT_KEY, String.valueOf(key.attempt()));
    }

    private void clear() {
        MDC.remove(STAGE_KEY);
        MDC.remove(ATTEMPT_KEY);
    }
}
