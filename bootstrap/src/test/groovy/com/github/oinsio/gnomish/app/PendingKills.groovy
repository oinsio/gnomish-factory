package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.PendingVerification
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.sandbox.Segment
import java.nio.file.Path
import org.jspecify.annotations.Nullable

/**
 * Kills a container {@code gnomish run} at a kill window of a pending verification (design D21 of
 * migrate-verify-to-executor-contract, task 4.3b), in process: the round's persistence throws {@link
 * RunKills.SimulatedKill} at the named step, so everything durable before it stays exactly as a
 * {@code SIGKILL} there would leave it (`.claude/rules/crash-consistency.md`, item 10). The snapshot
 * is the round source's and lands before any of them; the kill is an {@link Error}, so no catch
 * of the verification absorbs it. A dead process runs no {@code finally} block either: the box keep
 * the run's boundary reaches after the death — which stops the box and reconciles the task branch
 * with origin, pushing what the clone holds ahead of it — becomes a no-op, as for {@link
 * RunKillPoint#AFTER_PARK_COMMIT}, so origin keeps exactly what the dying instance pushed.
 */
final class PendingKills {

    /** The last durable step before the death, after the round's snapshot. */
    enum Point {
        /** W0: the snapshot registered; the attempt delivery an external check waits for never pushed it. */
        BEFORE_DELIVERY,
        /** W1, the snapshot pushed by the attempt delivery: {@code start} answered {@code pending}; its pending commit never landed. */
        BEFORE_PENDING_COMMIT,
        /** W1′: the pending commit landed in the factory clone; its push never reached origin. */
        PENDING_COMMIT_PUSH_LOST,
        /** W2: the pending commit landed and was pushed with its register. */
        AFTER_PENDING_COMMIT,
        /** W4: a {@code poll} answered terminally; the round's closing commit never landed. */
        AFTER_TERMINAL_POLL,
    }

    private PendingKills() {}

    /** Container: supports from {@code inner} whose round persistence dies at {@code point}. */
    static ContainerSupportFactory killedAt(Point point, ContainerSupportFactory inner) {
        { Path cloneDir, String taskId, List<Segment> segments, PipelineDefinition definition, List<String> scrub ->
            new KilledSupport(inner.create(cloneDir, taskId, segments, definition, scrub), cloneDir, point)
        } as ContainerSupportFactory
    }

    private static final class KilledSupport implements SandboxRunSupport {
        @Delegate
        private final SandboxRunSupport inner
        final Path cloneDir
        final Point point
        private boolean dead

        KilledSupport(SandboxRunSupport inner, Path cloneDir, Point point) {
            this.inner = inner
            this.cloneDir = cloneDir
            this.point = point
        }

        /** The death at the named step: everything after it, the run's {@code finally} included, is skipped. */
        RunKills.SimulatedKill die(String where) {
            dead = true
            new RunKills.SimulatedKill(where)
        }

        /** The box keep, skipped by a process that already died. */
        @Override
        void keepStopped() {
            if (!dead) {
                inner.keepStopped()
            }
        }

        @Override
        AttemptPersistence persistence() {
            new KilledPersistence(inner.persistence(), this)
        }

        @Override
        SandboxRunPieces pieces(@Nullable PendingVerification pendingVerification) {
            def pieces = inner.pieces(pendingVerification)
            if (point != Point.BEFORE_DELIVERY) {
                return pieces
            }
            def dying = { Workspace workspace ->
                throw die('after the snapshot, before its attempt delivery')
            } as AttemptDelivery
            new SandboxRunPieces(pieces.executorRounds(), pieces.judgeEnvironments(), pieces.checkEnvironments(), pieces.freshBoxes(),
                    pieces.attemptReader(), dying, pieces.rounds(), pieces.pendingVerification())
        }
    }

    private static final class KilledPersistence implements AttemptPersistence {
        private final AttemptPersistence inner
        private final KilledSupport support

        KilledPersistence(AttemptPersistence inner, KilledSupport support) {
            this.inner = inner
            this.support = support
        }

        @Override
        void persistPending(String taskId, TaskState state) {
            switch (support.point) {
                case Point.BEFORE_PENDING_COMMIT:
                    throw support.die('after start answered pending, before its pending commit')
                case Point.PENDING_COMMIT_PUSH_LOST:
                    PushBlackout.around(support.cloneDir) {
                        inner.persistPending(taskId, state)
                    }
                    throw support.die('after the pending commit, before its push')
                case Point.AFTER_PENDING_COMMIT:
                    inner.persistPending(taskId, state)
                    throw support.die('after the pending commit and its push')
                default:
                    inner.persistPending(taskId, state)
            }
        }

        @Override
        void persist(String taskId, TaskState state, ToolTrace trace) {
            if (support.point == Point.AFTER_TERMINAL_POLL) {
                throw support.die('after the terminal poll, before the round\'s closing commit')
            }
            inner.persist(taskId, state, trace)
        }
    }
}
