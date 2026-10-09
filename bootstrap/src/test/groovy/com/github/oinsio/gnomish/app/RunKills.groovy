package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.sandbox.Segment
import java.nio.file.Path

/**
 * Kills a {@code gnomish run} at a named {@link RunKillPoint} of its park's terminal boundary, in
 * process: the step throws {@code SimulatedKill} instead of running, so everything durable before it
 * stays exactly as a {@code SIGKILL} at that point would leave it (`.claude/rules/crash-consistency.md`,
 * item 10). For the window after the outcome commit a dead process runs no {@code finally} block
 * either: the container support's box keep becomes a no-op, so the frozen state keeps its box running
 * as the design table says. The kill before the commit keeps its original stand-in — the keep in the
 * boundary's {@code finally} still stops the box — because the resume specs built on it start from a
 * kept, stopped box and restart it themselves.
 *
 * <p>Before {@code make-run-headless} the specs that resume a "killed" task used an EOF at the
 * decision prompt as their stand-in for the kill: it left the branch with rounds and no outcome.
 * A stop now records its park, so those specs reach the same shape the honest way — by dying
 * before the park's outcome commit ({@link RunKillPoint#BEFORE_PARK_COMMIT}); the two later windows
 * are the kill-point rows of {@code RunParkKillPointSpec} and its container twin.
 */
final class RunKills {

    private RunKills() {}

    /** The process death, carried as an {@link Error} so no production catch absorbs it. */
    static final class SimulatedKill extends Error {
        SimulatedKill(String where) {
            super('simulated kill ' + where)
        }
    }

    /** Host: {@code git} whose lifecycle store dies at a park's outcome commit; nothing else changes. */
    static TaskGit killedBeforeParkRecord(TaskGit git) {
        killedAt(RunKillPoint.BEFORE_PARK_COMMIT, git)
    }

    /** Container: supports from {@code inner} that die at a park's outcome commit. */
    static ContainerSupportFactory killedBeforeParkRecord(ContainerSupportFactory inner) {
        killedAt(RunKillPoint.BEFORE_PARK_COMMIT, inner)
    }

    /** Host: {@code git} whose lifecycle store dies at {@code point} of a park; nothing else changes. */
    static TaskGit killedAt(RunKillPoint point, TaskGit git) {
        assert point != RunKillPoint.AFTER_PARK_PUSH: 'the host keeps nothing after the push — there is no step 3 to die before'
        def store = new KillingStoreGit(git.store(), point)
        new TaskGit(store, git.branches(), git.worktrees(), git.midRoundPush(), git.baseRefs(), git.epochs())
    }

    /** Container: supports from {@code inner} that die at {@code point} of a park. */
    static ContainerSupportFactory killedAt(RunKillPoint point, ContainerSupportFactory inner) {
        { Path cloneDir, String taskId, List<Segment> segments, PipelineDefinition definition, List<String> scrub ->
            new KilledAtParkSupport(inner.create(cloneDir, taskId, segments, definition, scrub), cloneDir, point)
        } as ContainerSupportFactory
    }

    /**
     * Container: supports from {@code inner} that die right after the first round's state commit —
     * the round is recorded, the next round never opens (FR17 of make-checkpoint-gate-durable,
     * task 8.3). Not a park window, so not a {@link RunKillPoint}: the keep in the run's {@code
     * finally} still stops the box, as for {@link RunKillPoint#BEFORE_PARK_COMMIT}.
     */
    static ContainerSupportFactory killedAfterFirstRoundRecord(ContainerSupportFactory inner) {
        { Path cloneDir, String taskId, List<Segment> segments, PipelineDefinition definition, List<String> scrub ->
            new KilledAfterRoundSupport(inner.create(cloneDir, taskId, segments, definition, scrub))
        } as ContainerSupportFactory
    }

    /**
     * The park's outcome commit, as {@code point} lets it land: not at all before the commit; committed
     * with its push lost after the commit; in full after the push.
     */
    private static void record(RunKillPoint point, Path cloneDir, Closure write) {
        switch (point) {
            case RunKillPoint.BEFORE_PARK_COMMIT:
                throw new SimulatedKill('before the park outcome commit')
            case RunKillPoint.AFTER_PARK_COMMIT:
                PushBlackout.around(cloneDir, write)
                throw new SimulatedKill('after the park outcome commit, before its push')
            default:
                write()
        }
    }

    private static final class KillingStoreGit implements TaskStoreGit {
        @Delegate
        private final TaskStoreGit inner
        private final RunKillPoint point

        KillingStoreGit(TaskStoreGit inner, RunKillPoint point) {
            this.inner = inner
            this.point = point
        }

        @Override
        TaskLifecycleStore taskRepository(RegisteredClone clone) {
            new KillingLifecycleStore(inner.taskRepository(clone), clone.clonePath(), point)
        }
    }

    private static final class KillingLifecycleStore implements TaskLifecycleStore {
        @Delegate
        private final TaskLifecycleStore inner
        private final Path cloneDir
        private final RunKillPoint point

        KillingLifecycleStore(TaskLifecycleStore inner, Path cloneDir, RunKillPoint point) {
            this.inner = inner
            this.cloneDir = cloneDir
            this.point = point
        }

        @Override
        void recordOutcome(String taskId, TaskOutcome outcome, TrackerWrite trackerWrite) {
            if (!TerminalPark.isPark(outcome)) {
                inner.recordOutcome(taskId, outcome, trackerWrite)
                return
            }
            record(point, cloneDir) {
                inner.recordOutcome(taskId, outcome, trackerWrite)
            }
        }
    }

    private static final class KilledAtParkSupport implements SandboxRunSupport {
        @Delegate
        private final SandboxRunSupport inner
        private final Path cloneDir
        private final RunKillPoint point
        private boolean parked
        private boolean dead

        KilledAtParkSupport(SandboxRunSupport inner, Path cloneDir, RunKillPoint point) {
            this.inner = inner
            this.cloneDir = cloneDir
            this.point = point
        }

        @Override
        void recordPark(TaskOutcome outcome, TrackerWrite trackerWrite) {
            parked = true
            dead = point == RunKillPoint.AFTER_PARK_COMMIT
            record(point, cloneDir) {
                inner.recordPark(outcome, trackerWrite)
            }
        }

        /**
         * The box keep: skipped by a process that already died, the death itself when it follows a
         * pushed park and the kill point is after the push. A keep reached any other way — a failure
         * out of the loop before any park — is the real one.
         */
        @Override
        void keepStopped() {
            if (dead) {
                return
            }
            if (parked && point == RunKillPoint.AFTER_PARK_PUSH) {
                dead = true
                throw new SimulatedKill('after the park push, before the box keep')
            }
            inner.keepStopped()
        }
    }

    private static final class KilledAfterRoundSupport implements SandboxRunSupport {
        @Delegate
        private final SandboxRunSupport inner

        KilledAfterRoundSupport(SandboxRunSupport inner) {
            this.inner = inner
        }

        @Override
        AttemptPersistence persistence() {
            def recorded = inner.persistence()
            return { taskId, state, trace ->
                recorded.persist(taskId, state, trace)
                throw new SimulatedKill('after the round state commit')
            } as AttemptPersistence
        }
    }
}
