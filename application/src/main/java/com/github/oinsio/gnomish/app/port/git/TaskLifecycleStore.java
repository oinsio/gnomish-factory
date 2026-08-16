package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.port.TaskRepository;

/**
 * A {@link TaskRepository} that also owns the durable "tracker-write pending" marker a terminal
 * park records (FR10, design D10 of add-claim-heartbeat): the park sets the marker as part of
 * recording its outcome, and clears it only once the git-unfenced tracker write confirms, so a
 * crash between the two leaves reconcile-on-resume something to find.
 *
 * <p>Kept as an extension rather than folded into {@link TaskRepository} so the base port's
 * contract is unchanged (FR9 of split-into-modules): the marker is a property of a durable,
 * branch-backed store, and the in-memory reference repository has nothing to confirm.
 *
 * <p>Implements FR10, D10 of add-claim-heartbeat; FR12b of split-into-modules.
 */
public interface TaskLifecycleStore extends TaskRepository {

    /**
     * Clears {@code taskId}'s tracker-write-pending marker, called once the park's tracker write
     * has confirmed.
     *
     * @param taskId the tracker's original taskId; never blank
     */
    void confirmTerminalWrite(String taskId);
}
