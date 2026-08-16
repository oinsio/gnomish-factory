package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;

/**
 * The register/unregister lifecycle seam the take run drives (task 6.1, design D3): the narrow
 * view of {@link InstanceHeartbeat} the claim choke point depends on, so the take path can start
 * beating a claim the instant it is acquired and stop beating it at the terminal result — without
 * depending on the whole beat-thread mechanism. {@link InstanceHeartbeat} is the production
 * realization; {@link #NONE} is the no-op used by call sites that hold no claim to beat (the
 * disposition/bare-auto unit specs), mirroring {@link ReaperDuty#NONE} in this same package.
 *
 * <p>Implements FR1 of add-claim-heartbeat.
 */
public interface ClaimBeat {

    /** The no-op lifecycle used where no heartbeat runs (a claimless unit spec). */
    ClaimBeat NONE = new ClaimBeat() {
        @Override
        public void register(TaskRef ref) {}

        @Override
        public void unregister(TaskRef ref) {}
    };

    /**
     * Begins beating {@code ref}, called the instant the claim is acquired; starts the beat thread
     * on the first claim (FR1).
     *
     * <p>Implements FR1 of add-claim-heartbeat.
     *
     * @param ref the just-claimed task to begin beating; never null
     */
    void register(TaskRef ref);

    /**
     * Stops beating {@code ref}, called at the terminal result, an exception, or a lost claim; the
     * beat thread stops itself after the next tick that finds no claim held (FR1).
     *
     * <p>Implements FR1 of add-claim-heartbeat.
     *
     * @param ref the task to stop beating; never null
     */
    void unregister(TaskRef ref);
}
