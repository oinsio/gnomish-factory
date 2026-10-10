package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import java.time.Duration;

/**
 * The timing equipment every box operation runs on (design D11 of add-parameter-count-gate): the
 * time equipment whose clock stamps exec starts and whose sleeper the self-check's pause waits on,
 * and the deadline every {@code docker} management command is bounded by (FR5, FR10, design D8 of
 * bound-subprocess-commands). All of it is about time; a host directory such as the guard config
 * root is deliberately not here — it names nothing beside a clock.
 *
 * <p>Implements FR6 of add-parameter-count-gate; FR22 of supervise-daemon-loops-and-embed-dashboard
 * (the clock and the sleeper arrive as the composition root's one {@link TimeEquipment}, design
 * D20).
 *
 * @param equipment the time equipment: the exec start-instant source and the guard-readiness pause
 *     seam of the self-check; never null
 * @param dockerCommandTimeout the hard bound on each {@code docker} management command — the
 *     installation's {@code factory.docker-command-timeout}; never null
 */
public record BoxTiming(TimeEquipment equipment, Duration dockerCommandTimeout) {}
