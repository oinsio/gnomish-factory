package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.ClaimBeat;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;

/**
 * The <em>claim tenure</em> as a slot sees it: the liveness view of one holding of a claim — the
 * beat that keeps the claim alive and the flag that reports its loss — handed to the take chain
 * as one value (design D1 of introduce-slot-wiring).
 *
 * <p>A narrower view of the run's {@link TakeHeartbeat}, not a second source: the heartbeat owns
 * both values and requires the flag to be the SAME instance wired as the beat's lost-claim sink,
 * so production builds a tenure only through {@link TakeHeartbeat#tenure()}. A tenure assembled
 * from any other flag would silently detach the round-boundary consult from the beat. Specs may
 * pair {@link ClaimBeat#NONE} with a fresh flag, as they pass that pair today.
 *
 * <p>Deliberately does NOT carry the claim epoch that identifies the tenure: {@code
 * TaskGit.epochs()} is that book's single owner (design D5), and a second carrier here would be a
 * second owner of one value.
 *
 * <p>Implements FR3 of introduce-slot-wiring.
 *
 * @param beat the register/unregister lifecycle the claim choke point drives; never null
 * @param lossFlag the flag the beat sets on a lost claim and the take flow consults at each round
 *     boundary; never null
 */
public record ClaimTenure(ClaimBeat beat, ClaimLossFlag lossFlag) {}
