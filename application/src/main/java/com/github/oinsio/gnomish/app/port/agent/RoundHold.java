package com.github.oinsio.gnomish.app.port.agent;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The open round a {@link RoundEnvironmentSource} holds, from its open to its close or discard
 * (design D14 of extract-agent-round-runner, as amended 2026-11-25): one round at a time, so a
 * second open while one is held is refused before anything is minted or materialized, and a poll
 * takes the held round instead of opening one. Every round source holds its round through this one
 * class, so the rule is written once for the host source, its git-mode decoration and the box source.
 *
 * <p>It also remembers whether the source ever held a round: a source that never did is a resuming
 * instance's, whose first poll continues the round the run's cell was restored with; a source that
 * held one and let it go continues nothing — the closed round is not adopted again.
 *
 * <p>Thread-safety: the held round is an {@link AtomicReference} and the history flag a {@code
 * volatile}, so a poll on another thread sees a whole hold or none; no lock is taken, so nothing
 * blocks under one (lock-scope rule). Within a run, rounds are sequential.
 *
 * <p>Implements FR10 of extract-agent-round-runner.
 *
 * @param <R> the source's own round type
 */
public final class RoundHold<R> {

    private final AtomicReference<@Nullable R> held = new AtomicReference<>();
    private volatile boolean everHeld;

    /**
     * Refuses an open while a round is held; called first, so a refused open mints nothing.
     *
     * @throws IllegalStateException if a round is held
     */
    public void refuseOpenWhileHeld() {
        if (held.get() != null) {
            throw new IllegalStateException(
                    "a round of this source is still open: a second open is refused until its close or discard");
        }
    }

    /**
     * Holds {@code round}, the round just opened or continued from the run's cell.
     *
     * @param round the round to hold; never null
     * @return {@code round}
     * @throws IllegalStateException if another round was held meanwhile
     */
    public R hold(R round) {
        if (!held.compareAndSet(null, round)) {
            throw new IllegalStateException("a round of this source is still open: it cannot hold a second");
        }
        everHeld = true;
        return round;
    }

    /**
     * The held round.
     *
     * @return the open round, or empty when none is held; never null
     */
    public Optional<R> held() {
        return Optional.ofNullable(held.get());
    }

    /**
     * Whether this source ever held a round: {@code false} only for a resuming instance's source
     * before its first round.
     *
     * @return whether a round was ever held
     */
    public boolean everHeld() {
        return everHeld;
    }

    /**
     * Lets {@code round} go at its close or discard; a round no longer held changes nothing, so a
     * discard after the close is a no-op.
     *
     * @param round the round ending; never null
     */
    public void release(R round) {
        held.compareAndSet(round, null);
    }
}
