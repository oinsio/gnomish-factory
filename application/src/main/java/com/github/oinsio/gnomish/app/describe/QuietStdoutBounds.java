package com.github.oinsio.gnomish.app.describe;

import com.github.oinsio.gnomish.executorcontract.Capability;
import com.github.oinsio.gnomish.executorcontract.Describe;

/**
 * Whether the bounds on a quiet stdout apply to an executor's runs (FR16 of extract-agent-round-runner;
 * design D6, "The silence bound follows the dialect's declaration", and its single-owner row;
 * wire-contract "Silence bound expires"): yes when its describe declares {@code streams_progress}, no
 * otherwise. A silence bound is sound only for a worker that reports liveness; an executor that does
 * not declare it may stay quiet for its whole run and is held to the overall bound of {@link
 * ProgramBounds} alone.
 *
 * <p>The one reader of {@link Capability#STREAMS_PROGRESS}: the {@code program} executor's wait and
 * the agent round's bounds ({@code RoundBounds}, which also drops the startup-read bound and both
 * hints) take the answer from here, and {@code RoundBoundsOwnerSpec} keeps the capability read
 * nowhere else.
 *
 * <p>Implements FR16 of extract-agent-round-runner.
 */
public final class QuietStdoutBounds {

    private QuietStdoutBounds() {}

    /**
     * Whether the startup-read and silence bounds apply to the runs of the executor that answered
     * {@code describe}.
     *
     * @param describe the executor's describe answer; never null
     * @return true when it declares {@code streams_progress}
     */
    public static boolean applyTo(Describe describe) {
        return describe.capabilities().contains(Capability.STREAMS_PROGRESS);
    }
}
