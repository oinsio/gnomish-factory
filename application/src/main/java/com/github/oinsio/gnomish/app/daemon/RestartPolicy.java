package com.github.oinsio.gnomish.app.daemon;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * What a {@link SupervisedLoop} does when its thread dies despite the in-loop guard (design D5 of
 * supervise-daemon-loops-and-embed-dashboard): the second rung of supervision. Both policies wait
 * a doubling backoff ({@link RestartBackoff}) before a respawn and keep a lifetime restart count;
 * they differ only in whether they ever give up. Choose {@link Unbounded} for a loop the factory's
 * correctness or the operator's view depends on, {@link Bounded} for an optional loop whose
 * repeated death means a bug a restart will not cure.
 *
 * <p>This file is the only place a supervised loop's {@link RestartBackoff} is constructed (the
 * single-owner table of the change's design; pinned by {@code DaemonLoopOwnerBoundarySpec}).
 * The steps are package-private: only the loop drives them; owners read {@link #restartCount()}.
 *
 * <p>Implements FR3, FR6 of supervise-daemon-loops-and-embed-dashboard.
 */
public abstract sealed class RestartPolicy permits RestartPolicy.Unbounded, RestartPolicy.Bounded {

    private RestartPolicy() {}

    /** Decides the outcome of one death; called under the loop's lock, so it never blocks. */
    abstract RestartDecision onDeath();

    /** A tick completed cleanly: the next death backs off from the base again. */
    abstract void markCleanTick();

    /**
     * How many times this loop has been respawned after a death, over its whole life.
     *
     * @return the lifetime restart count
     */
    public abstract int restartCount();

    /** Respawns after every death, forever; the ERROR line's rising count is the alarm. */
    public static final class Unbounded extends RestartPolicy {

        private final Duration firstBackoff;
        private final RestartBackoff backoff;

        /**
         * @param base the first backoff after a clean run, typically the loop's interval; never null
         * @param cap the longest backoff; when it is below {@code base}, the cap wins from the first
         *     respawn on; never null
         */
        public Unbounded(Duration base, Duration cap) {
            this.firstBackoff = Collections.min(List.of(base, cap));
            this.backoff = new RestartBackoff(cap);
        }

        @Override
        RestartDecision onDeath() {
            return new RestartDecision.Respawn(backoff.nextBackoff(firstBackoff), backoff.nextRestartCount());
        }

        @Override
        void markCleanTick() {
            backoff.markCleanTick();
        }

        @Override
        public int restartCount() {
            return backoff.restartCount();
        }
    }

    /**
     * Respawns like {@link Unbounded} until more than {@code maxRestarts} restarts would fall within
     * {@code window}; that death is answered with {@link RestartDecision.GiveUp} and no respawn.
     */
    public static final class Bounded extends RestartPolicy {

        private final Unbounded respawns;
        private final int maxRestarts;
        private final Duration window;
        private final InstantSource clock;
        private final Deque<Instant> recentRestarts = new ArrayDeque<>();

        /**
         * @param base the first backoff after a clean run; never null
         * @param cap the longest backoff; never null
         * @param maxRestarts the restarts allowed within {@code window}; the next death gives up
         * @param window the sliding period restarts are counted over; never null
         * @param clock the time source the window is measured on; never null
         */
        public Bounded(Duration base, Duration cap, int maxRestarts, Duration window, InstantSource clock) {
            this.respawns = new Unbounded(base, cap);
            this.maxRestarts = maxRestarts;
            this.window = window;
            this.clock = clock;
        }

        @Override
        synchronized RestartDecision onDeath() {
            Instant now = clock.instant();
            Instant horizon = now.minus(window);
            recentRestarts.removeIf(at -> !at.isAfter(horizon));
            if (recentRestarts.size() >= maxRestarts) {
                return new RestartDecision.GiveUp(respawns.restartCount(), maxRestarts, window);
            }
            recentRestarts.addLast(now);
            return respawns.onDeath();
        }

        @Override
        void markCleanTick() {
            respawns.markCleanTick();
        }

        @Override
        public int restartCount() {
            return respawns.restartCount();
        }
    }
}
