package com.github.oinsio.gnomish.app

/**
 * Where a {@code gnomish run} dies on its way through a park's terminal boundary — the kill windows
 * of design D8 of make-run-headless ("Crash consistency of the park"), each named after the last
 * durable step that landed before the death. {@link RunKills} realizes every one in process.
 *
 * <p>The durable steps, in order, after the engine's last round commit: (1) the park's outcome
 * commit, (2) its best-effort push by the repository decorator, (3) the workspace keep — nothing on
 * the host, {@code keepStopped} in the container.
 */
enum RunKillPoint {

    /**
     * After the last round commit, before step 1: rounds present, no outcome — the interrupted-run
     * shape ({@code InProgress}). Converges only for an {@code AttemptsExhausted} escalation, whose
     * spent limit is in the recorded state; the pre-park windows of the other stops belong to the
     * follow-up change {@code make-checkpoint-gate-durable}.
     */
    BEFORE_PARK_COMMIT,

    /** After step 1, before step 2 reached origin: parked locally, origin behind. */
    AFTER_PARK_COMMIT,

    /**
     * After step 2, before step 3 — container only, since the host has no step 3: parked on origin,
     * the box still running.
     */
    AFTER_PARK_PUSH
}
