package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.EngineEvent;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The heartbeat's progress source (design D1): an {@link EngineEventListener} that keeps,
 * per task, the latest {@code (stage, attempt)} it has seen on the engine's event stream,
 * so a beat can write a human-readable progress line into the claim marker. It is one more
 * listener registered on the engine's composite (task 6.1 wires it), never a run effect —
 * a beat reports progress, it never drives it.
 *
 * <p><b>Which events it reads.</b> The five per-attempt events ({@code AttemptStarted},
 * {@code ExecutionFinished}, {@code CheckStarted}, {@code CheckFinished}, {@code
 * AttemptFinished}) each carry an {@link AttemptKey}, whose {@code stage} and {@code
 * attempt} are recorded verbatim; {@code RunStarted} carries a {@link Position} (its
 * resolved stage) and the attempts already burned, giving a meaningful line before the
 * first attempt event; {@code TaskFinished} carries no {@code (stage, attempt)} and is a
 * no-op — the last snapshot stands until the task is unregistered.
 *
 * <p><b>Thread-safety.</b> The engine thread writes through {@link #onEvent} while the
 * heartbeat thread reads through {@link #progressFor}. The store is a {@link
 * ConcurrentHashMap} of immutable {@link Progress} records, so the two threads never
 * corrupt one another and a reader always sees a coherent snapshot. Honouring the port
 * contract, {@link #onEvent} only updates that in-memory snapshot: it returns promptly and
 * never throws past the call (a map put cannot), so a slow or failing listener never
 * burdens the engine's critical path.
 *
 * <p>Implements FR1 of add-claim-heartbeat.
 */
public final class HeartbeatProgress implements EngineEventListener {

    /**
     * The line shown for a freshly claimed task before any engine event has arrived.
     *
     * <p>Made public (beyond add-claim-heartbeat's original package-private scope) for
     * add-serve-observability's slot-entry enrichment (FR6, D11): the assembler that turns this
     * sentinel into a {@code null} {@code SlotEntry.stage} lives outside this package.
     */
    public static final Progress PENDING = new Progress("(pending)", 0);

    /**
     * The stage recorded when {@code RunStarted} carries a resolved {@link
     * Position.PipelineEnd} position — the task has finished the pipeline. Public for the same
     * reason as {@link #PENDING}: add-serve-observability's slot-entry enrichment (FR6, D11)
     * maps it to a {@code null} {@code SlotEntry.stage}.
     */
    public static final String PIPELINE_END_STAGE = "(end)";

    private final Map<String, Progress> byTask = new ConcurrentHashMap<>();

    /**
     * Records the latest {@code (stage, attempt)} the event carries, keyed by its task id
     * (the same opaque id a {@code TaskRef} carries). Attempt-key events record the key's
     * stage and attempt; {@code RunStarted} records its resolved position and burned
     * attempts; {@code TaskFinished} is a no-op. Returns promptly and never throws past
     * this call, per the {@link EngineEventListener} contract.
     *
     * <p>Implements FR1 of add-claim-heartbeat.
     *
     * @param event the event that just occurred; never null
     */
    @Override
    public void onEvent(EngineEvent event) {
        switch (event) {
            case EngineEvent.RunStarted started ->
                byTask.put(started.taskId(), new Progress(stageOf(started.position()), started.attemptsUsed()));
            case EngineEvent.AttemptStarted e -> record(e.key());
            case EngineEvent.ExecutionFinished e -> record(e.key());
            case EngineEvent.CheckStarted e -> record(e.key());
            case EngineEvent.CheckFinished e -> record(e.key());
            case EngineEvent.AttemptFinished e -> record(e.key());
            case EngineEvent.TaskFinished ignored -> {
                // The terminal bookend carries no (stage, attempt); the last snapshot stands.
            }
        }
    }

    /**
     * Returns the latest progress recorded for {@code taskId}, or {@link #PENDING} when no
     * event has arrived for it yet (a claim registered before its first engine event).
     *
     * <p>Implements FR1 of add-claim-heartbeat.
     *
     * @param taskId the engine task id, equal to the held {@code TaskRef}'s id; never null
     * @return the latest progress snapshot; never null
     */
    public Progress progressFor(String taskId) {
        return byTask.getOrDefault(taskId, PENDING);
    }

    private void record(AttemptKey key) {
        byTask.put(key.taskId(), new Progress(key.stage(), key.attempt()));
    }

    private static String stageOf(Position position) {
        return switch (position) {
            case Position.AtStage atStage -> atStage.name();
            case Position.PipelineEnd ignored -> PIPELINE_END_STAGE;
        };
    }

    /**
     * The latest known position of a task in its pipeline: the {@code stage} name and the
     * {@code attempt} (round) number the beat writes into the claim marker. Immutable value
     * shared across threads by content.
     *
     * @param stage the stage name last seen for the task; never blank
     * @param attempt the round number last seen; never negative
     */
    public record Progress(String stage, int attempt) {}
}
