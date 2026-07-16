package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskOutcome

/**
 * The event-folding support for the status-reconstruction specs (task 6.3, NFR-O2): a pure
 * status view a downstream consumer would build, plus the fold that rebuilds it from a
 * recorded {@link EngineEvent} stream ALONE — never reading engine internals or the returned
 * outcome. Extracted so the reconstruction specs hold only the scenarios. Test support for
 * the add-stage-engine ports; not production code.
 */
class StatusViewSupport {

    /**
     * A pure status view a downstream consumer would build. Holds ONLY what the event
     * stream carries: the reconstructed position, attempt counters, and per-round
     * detail (its key, check verdicts and executor usage) — no engine internals.
     */
    static class StatusView {
        Position position
        // the authoritative live counter from the terminal final state (reset to 0 after a
        // completing advancement — the fresh next-stage state)
        int attemptsUsed
        // the burned-attempt count of the last stage that actually ran, read from the LAST
        // AttemptFinished.newState() BEFORE advancement — this is where a retry's burn stays visible
        int lastStageAttemptsUsed
        int rounds
        // per round, in order: the AttemptKey the round's events shared
        List<AttemptKey> roundKeys = []
        // per round, in order: the CheckResults collected from CheckFinished events
        List<List<CheckResult>> roundCheckResults = []
        // per round, in order: the ExecutorUsage from the round's ExecutionFinished
        List<ExecutorUsage> roundUsages = []
        // the terminal outcome from TaskFinished
        TaskOutcome terminalOutcome
    }

    /**
     * The reconstruction fold — reads ONLY the recorded events, never the engine or the
     * returned outcome. The authoritative terminal position / counters come from
     * {@code TaskFinished.outcome().finalState()} (the run-level bookend that carries the
     * post-advancement state the per-round {@code AttemptFinished} events cannot, since
     * advancement to {@code PipelineEnd} happens after the last round and is not itself an
     * event). Per-round history is grouped by the shared {@link AttemptKey}: check verdicts
     * from {@code CheckFinished}, executor usage from {@code ExecutionFinished}, and each
     * round's recorded state from {@code AttemptFinished.newState()} (NFR-O2).
     */
    static StatusView reconstruct(List<EngineEvent> events) {
        def view = new StatusView()

        def taskFinished = events.find { it instanceof EngineEvent.TaskFinished }
        view.terminalOutcome = taskFinished == null ? null : (taskFinished as EngineEvent.TaskFinished).outcome()

        // authoritative terminal position / counters: the TaskFinished bookend's final state.
        if (view.terminalOutcome != null) {
            view.position = view.terminalOutcome.finalState().position()
            view.attemptsUsed = view.terminalOutcome.finalState().attemptsUsed()
        }

        def finishedEvents = events.findAll { it instanceof EngineEvent.AttemptFinished }
        view.rounds = finishedEvents.size()

        // the last-executed stage's burned count, from the LAST AttemptFinished's recorded
        // state (pre-advancement) — where a retry's burn stays visible after completion resets it.
        if (!finishedEvents.isEmpty()) {
            view.lastStageAttemptsUsed = (finishedEvents.last() as EngineEvent.AttemptFinished).newState().attemptsUsed()
        }

        // per round, ordered by AttemptFinished, collect the shared key, the check
        // results (from CheckFinished with the same key) and the executor usage.
        finishedEvents.each { fin ->
            def key = (fin as EngineEvent.AttemptFinished).key()
            view.roundKeys << key

            def checks = events.findAll {
                it instanceof EngineEvent.CheckFinished && (it as EngineEvent.CheckFinished).key() == key
            }.collect { (it as EngineEvent.CheckFinished).result() }
            view.roundCheckResults << checks

            def exec = events.find {
                it instanceof EngineEvent.ExecutionFinished && (it as EngineEvent.ExecutionFinished).key() == key
            }
            view.roundUsages << (exec == null ? null : (exec as EngineEvent.ExecutionFinished).usage())
        }

        view
    }
}
