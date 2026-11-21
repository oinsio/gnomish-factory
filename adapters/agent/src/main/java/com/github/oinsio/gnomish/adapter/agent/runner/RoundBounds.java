package com.github.oinsio.gnomish.adapter.agent.runner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.adapter.agent.program.ProgressWait;
import com.github.oinsio.gnomish.adapter.settings.RoundTimeout;
import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.executorcontract.Bounds;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import java.time.Duration;
import java.util.Map;

/**
 * The one resolution of a round's three time bounds (FR7, NFR-R1 of extract-agent-round-runner; design
 * D6 as amended 2026-11-21, the {@code RoundBounds} row): the factory's startup-read and silence bounds,
 * and the overall bound from the {@code roundTimeout} the round's settings declare — a stage's executor
 * settings, a judge check's settings — or the factory's overall bound when they declare none. The
 * startup-read and silence bounds have no settings key: they are properties of the CLI, not of the
 * stage's work. No factory ceiling sits over a declared value (proposal NG7).
 *
 * <p>The value the round's wait is built over ({@link #waitOn}) and the time hints of the request the
 * dialect renders ({@link #hinted}) are this one value, so the bound a round is killed at and the bound
 * it was told cannot diverge. Consumers: the agent round's wait in every role ({@code AgentRound}), the
 * {@code command} executor's timeout, and, until their deletion, the legacy executor and judge rounds;
 * {@code RoundBoundsOwnerSpec} keeps {@code RoundTimeout.declared(} here alone.
 *
 * <p>Implements FR7, NFR-R1 of extract-agent-round-runner.
 */
public final class RoundBounds {

    private final ProgramBounds bounds;

    private RoundBounds(ProgramBounds bounds) {
        this.bounds = bounds;
    }

    /**
     * The bounds of a round over {@code settings}.
     *
     * @param factory the factory's bounds; never null
     * @param settings the round's settings; never null, may be empty
     * @return the round's bounds; never null
     * @throws IllegalArgumentException when the settings declare a {@code roundTimeout} that is not a
     *     positive duration — no bound at all
     */
    public static RoundBounds of(ProgramBounds factory, Map<String, ?> settings) {
        Duration overall = RoundTimeout.declared(settings).orElse(factory.overall());
        return new RoundBounds(new ProgramBounds(factory.startup(), factory.silence(), overall));
    }

    /**
     * The bounds of a round of {@code request}, over the request's settings tree.
     *
     * @param factory the factory's bounds; never null
     * @param request the round's request; never null
     * @return the round's bounds; never null
     * @throws IllegalArgumentException as {@link #of(ProgramBounds, Map)}
     */
    public static RoundBounds of(ProgramBounds factory, ExecutorRequest request) {
        ObjectNode settings = request.settings();
        return of(factory, ExecutorContractJson.settingsValues(settings));
    }

    /** The three bounds as one value. */
    public ProgramBounds bounds() {
        return bounds;
    }

    /** The overall bound: the declared {@code roundTimeout}, or the factory's. */
    public Duration overall() {
        return bounds.overall();
    }

    /**
     * {@code request} with these bounds as its time hints, its turn, token and money hints as they came.
     *
     * @param request the round's request; never null
     * @return the request the dialect renders; never null
     */
    public ExecutorRequest hinted(ExecutorRequest request) {
        Bounds asked = request.bounds();
        Bounds times = bounds.hints();
        return new ExecutorRequest(
                request.role(),
                request.workspace(),
                request.policy(),
                request.roleInputs(),
                request.settings(),
                new Bounds(
                        asked.turns(),
                        asked.tokens(),
                        asked.moneyUsd(),
                        times.startupReadMillis(),
                        times.silenceMillis(),
                        times.overallMillis()),
                request.feedback(),
                request.continuation());
    }

    /**
     * The round's wait under these bounds.
     *
     * @param time the run's time equipment, shared with the reader's clock; never null
     * @return the wait; never null
     */
    ProgressWait waitOn(TimeEquipment time) {
        return new ProgressWait(bounds.startup(), bounds.silence(), bounds.overall(), time);
    }
}
