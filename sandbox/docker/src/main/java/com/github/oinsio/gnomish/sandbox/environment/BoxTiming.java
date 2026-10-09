package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.time.Duration;
import java.time.InstantSource;

/**
 * The timing equipment every box operation runs on (design D11 of add-parameter-count-gate): the
 * clock that stamps exec starts, the pause the self-check waits with, and the deadline every
 * {@code docker} management command is bounded by (FR5, FR10, design D8 of
 * bound-subprocess-commands). All three are about time; a host directory such as the guard
 * config root is deliberately not here — it names nothing beside a clock.
 *
 * <p>Implements FR6 of add-parameter-count-gate.
 *
 * @param clock the exec start-instant source; never null
 * @param sleeper the guard-readiness pause seam of the self-check; never null
 * @param dockerCommandTimeout the hard bound on each {@code docker} management command — the
 *     installation's {@code factory.docker-command-timeout}; never null
 */
public record BoxTiming(InstantSource clock, Sleeper sleeper, Duration dockerCommandTimeout) {}
