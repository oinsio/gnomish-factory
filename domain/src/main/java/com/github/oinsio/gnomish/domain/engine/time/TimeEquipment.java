package com.github.oinsio.gnomish.domain.engine.time;

import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;

/**
 * The <em>time equipment</em>: real time's two seams as one value — the current instant ({@link
 * InstantSource}) and waiting ({@link Sleeper}) (design D20 of
 * supervise-daemon-loops-and-embed-dashboard). Every policy the factory builds from time — a retry,
 * a loop's wait, a poll deadline, a box's exec stamp — needs both, so the pair travels as this one
 * value from the composition root, which builds the real one exactly once, down to the leaf that
 * uses it. A spec builds the virtual one ({@code VirtualTimeEquipment} in {@code :test-fixtures})
 * instead, so the production carrier and the test carrier are one type.
 *
 * <p><b>Why a value and not two parameters</b> — ADR 0010's three questions
 * ({@code docs/adr/0010-facade-over-parameter-object.md}):
 *
 * <ul>
 *   <li><b>(a) Used together, or meaningless apart.</b> Every holder of the pair consumes both
 *       halves in one decision: a retry measures its bound on the clock and backs off on the
 *       sleeper, a loop sleeps its interval and stamps {@code lastRunAt}, the poll loop waits
 *       between polls toward a deadline taken on the clock. A sleeper without the clock it is
 *       advancing is a wait nobody can measure; under test the two are coupled outright, since the
 *       virtual sleeper advances the virtual clock.
 *   <li><b>(b) Behavior beyond accessors.</b> {@link #remaining(Instant)} is the deadline test every
 *       bounded loop spelled privately ({@code !clock.instant().isBefore(deadline)} in {@code
 *       TerminalWriteRetry} and {@code ExternalPolling}, which now ask this value); {@link
 *       #sleepUntil(Instant)} is the two-member computation — sleep on the one half for what the
 *       other half says is left — that a deadline-bounded wait needs and that only the pair can do.
 *   <li><b>(c) A name the reader already has.</b> The glossary's feed-assembly and box-timing
 *       entries already called the pair "timing equipment"; "time equipment" is its own entry now.
 * </ul>
 *
 * <p>The real equipment is built in one place, the composition root ({@code :bootstrap}), which is
 * also the only module that can construct the real sleeper. {@code RepeatSuppressor} deliberately
 * takes a bare {@link InstantSource}: it never waits.
 *
 * <p>Implements FR18, FR22, G5 of supervise-daemon-loops-and-embed-dashboard.
 *
 * @param clock the source of the current instant; never null
 * @param sleeper the waiting seam, which advances {@code clock} under test; never null
 */
public record TimeEquipment(InstantSource clock, Sleeper sleeper) {

    /**
     * Refuses a missing half where the value is built: the equipment reaches plugins through the SPI
     * contexts, and a null half would otherwise fail late, inside a plugin's first {@link
     * #remaining} or {@link #sleepUntil} call.
     */
    public TimeEquipment {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * How long is left until {@code deadline} on this equipment's clock — {@link Duration#ZERO} once
     * the deadline is reached or passed, never negative. A bounded loop asks {@code
     * remaining(deadline).isZero()} for "time is up".
     *
     * <p>Implements FR22 of supervise-daemon-loops-and-embed-dashboard.
     *
     * @param deadline the instant a bounded wait ends at; never null
     * @return the time left, clamped at zero; never null
     */
    public Duration remaining(Instant deadline) {
        Duration left = Duration.between(clock.instant(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * Waits on this equipment's sleeper until {@code deadline} on its clock; returns at once, without
     * sleeping, when the deadline is already reached.
     *
     * <p>Implements FR22 of supervise-daemon-loops-and-embed-dashboard.
     *
     * @param deadline the instant to wait until; never null
     */
    public void sleepUntil(Instant deadline) {
        Duration left = remaining(deadline);
        if (!left.isZero()) {
            sleeper.sleep(left);
        }
    }
}
