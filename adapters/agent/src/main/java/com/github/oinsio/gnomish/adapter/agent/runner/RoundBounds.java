package com.github.oinsio.gnomish.adapter.agent.runner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.adapter.agent.program.ProgressWait;
import com.github.oinsio.gnomish.adapter.settings.RoundTimeout;
import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.app.describe.QuietStdoutBounds;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.executorcontract.Bounds;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The one resolution of a round's time bounds (FR7, FR16, NFR-R1 of extract-agent-round-runner; design
 * D6 as amended 2026-11-21 and 2026-11-22, the {@code RoundBounds} row and the row of the bounds on a
 * quiet stdout): the overall bound from the {@code roundTimeout} the round's settings declare — a
 * stage's executor settings, a judge check's settings — or the factory's overall bound when they
 * declare none; and the factory's startup-read and silence bounds when the dialect's describe declares
 * {@code streams_progress} ({@link QuietStdoutBounds}), none otherwise, so a dialect that honestly
 * declares no liveness is held to the overall bound alone. The startup-read and silence bounds have no
 * settings key: they are properties of the CLI, not of the stage's work. No factory ceiling sits over a
 * declared value (proposal NG7).
 *
 * <p>The value the round's wait is built over ({@link #waitOn}) and the time hints of the request the
 * dialect renders ({@link #hinted}) are this one value, so the bound a round is killed at and the bound
 * it was told cannot diverge, and a bound the round does not apply is not hinted. An instance is built
 * only over a describe ({@link #of}); a consumer that needs the overall bound alone — the {@code
 * command} executor's timeout and, until their deletion, the legacy executor and judge rounds — reads
 * {@link #overall(ProgramBounds, Map)}, which builds no wait. {@code RoundBoundsOwnerSpec} keeps {@code
 * RoundTimeout.declared(} here alone.
 *
 * <p>Implements FR7, FR16, NFR-R1 of extract-agent-round-runner.
 */
public final class RoundBounds {

    private final ProgramBounds bounds;
    private final boolean quietBounds;

    private RoundBounds(ProgramBounds bounds, boolean quietBounds) {
        this.bounds = bounds;
        this.quietBounds = quietBounds;
    }

    /**
     * The overall bound of a round over {@code settings}: the {@code roundTimeout} they declare, or the
     * factory's.
     *
     * @param factory the factory's bounds; never null
     * @param settings the round's settings; never null, may be empty
     * @return the overall bound; never null
     * @throws IllegalArgumentException when the settings declare a {@code roundTimeout} that is not a
     *     positive duration — no bound at all
     */
    public static Duration overall(ProgramBounds factory, Map<String, ?> settings) {
        return resolved(factory, settings).overall();
    }

    /**
     * The overall bound of a round of {@code request}, over the request's settings tree.
     *
     * @param factory the factory's bounds; never null
     * @param request the round's request; never null
     * @return the overall bound; never null
     * @throws IllegalArgumentException as {@link #overall(ProgramBounds, Map)}
     */
    public static Duration overall(ProgramBounds factory, ExecutorRequest request) {
        return resolved(factory, request).overall();
    }

    /**
     * The bounds of a round of {@code request} run by the dialect that answered {@code describe}.
     *
     * @param factory the factory's bounds; never null
     * @param describe the dialect's describe answer; never null
     * @param request the round's request; never null
     * @return the round's bounds; never null
     * @throws IllegalArgumentException as {@link #overall(ProgramBounds, Map)}
     */
    public static RoundBounds of(ProgramBounds factory, Describe describe, ExecutorRequest request) {
        return new RoundBounds(resolved(factory, request), QuietStdoutBounds.applyTo(describe));
    }

    private static ProgramBounds resolved(ProgramBounds factory, ExecutorRequest request) {
        ObjectNode settings = request.settings();
        return resolved(factory, ExecutorContractJson.settingsValues(settings));
    }

    /** The factory's bounds with the declared overall one, refused by {@link ProgramBounds} unless positive. */
    private static ProgramBounds resolved(ProgramBounds factory, Map<String, ?> settings) {
        Duration overall = RoundTimeout.declared(settings).orElse(factory.overall());
        return new ProgramBounds(factory.startup(), factory.silence(), overall);
    }

    /** The overall bound: the declared {@code roundTimeout}, or the factory's. */
    public Duration overall() {
        return bounds.overall();
    }

    /**
     * {@code request} with these bounds as its time hints — no startup-read and no silence hint when the
     * bounds on a quiet stdout do not apply — its turn, token and money hints as they came.
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
                        quietBounds ? times.startupReadMillis() : null,
                        quietBounds ? times.silenceMillis() : null,
                        times.overallMillis()),
                request.feedback(),
                request.continuation());
    }

    /**
     * The round's wait under these bounds: all three when the bounds on a quiet stdout apply, the
     * overall bound alone otherwise. The one place an agent round's wait is built.
     *
     * @param time the run's time equipment, shared with the reader's clock; never null
     * @return the wait; never null
     */
    ProgressWait waitOn(TimeEquipment time) {
        return new ProgressWait(quiet(bounds.startup()), quiet(bounds.silence()), bounds.overall(), time);
    }

    private @Nullable Duration quiet(Duration bound) {
        return quietBounds ? bound : null;
    }
}
