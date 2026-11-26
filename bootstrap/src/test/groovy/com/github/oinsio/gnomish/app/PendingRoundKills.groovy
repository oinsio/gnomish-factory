package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where an instance of {@link PendingRoundResumeSpecBase} dies, one factory per kill window of a pending
 * {@code transform} round (NFR-R3 of extract-agent-round-runner; design D11 as amended 2026-11-25,
 * crash-consistency item 10): each kill throws {@link Killed} just before the window's next durable step,
 * so what the register holds is exactly what the step before it left.
 */
final class PendingRoundKills {

    private PendingRoundKills() {
    }

    /** The instance dies at the named step, before the step's durable effect. */
    static final class Killed extends RuntimeException {
        Killed() {
            super('the first instance was killed')
        }
    }

    /** Where an instance dies: around its stage port, around its persistence, and the git it lands with. */
    static final class Kill {
        Closure<StageExecutor> port = { StageExecutor it -> it }
        Closure<AttemptPersistence> persistence = { AttemptPersistence it ->
            it
        }
        GitProcessRunner git
    }

    /** Dies at its {@code n}-th poll, before the request reaches the executor (K2). */
    static Kill atPoll(int n) {
        int polls = 0
        new Kill(port: { StageExecutor port ->
            [execute: { StageExecutor.Request request ->
                    port.execute(request)
                },
                poll: { StageExecutor.Request request, turn ->
                    if (++polls == n) {
                        throw new Killed()
                    }
                    port.poll(request, turn)
                }] as StageExecutor
        })
    }

    /** Dies at its {@code n}-th pending write, before anything is written (K1 after the snapshot, K3). */
    static Kill atPendingWrite(int n) {
        int writes = 0
        new Kill(persistence: { AttemptPersistence persistence ->
            [persistPending: { String taskId, TaskState state ->
                    if (++writes == n) {
                        throw new Killed()
                    }
                    persistence.persistPending(taskId, state)
                },
                persist: { String taskId, TaskState state, ToolTrace trace ->
                    persistence.persist(taskId, state, trace)
                }
            ] as AttemptPersistence
        })
    }

    /** Dies at the round's close, after its snapshot was registered and verified (K5). */
    static Kill atClose() {
        new Kill(persistence: { AttemptPersistence persistence ->
            [persistPending: { String taskId, TaskState state ->
                    persistence.persistPending(taskId, state)
                },
                persist: { String taskId, TaskState state, ToolTrace trace ->
                    throw new Killed()
                }] as AttemptPersistence
        })
    }

    /**
     * Dies at the first ref transaction after its {@code n}-th request {@code op} reached the executor: the
     * executor answers, and the snapshot that answer would register lands nothing (K1 before the snapshot,
     * K4). The instance's git is the real binary behind a stand-in link, re-pointed at the committed {@code
     * fail-subcommand} preset — named {@code update-ref} — just before that request.
     */
    static Kill refusingRefsAt(Path dir, String op, int n) {
        def link = StandIn.link(Files.createDirectories(dir).resolve('update-ref'), 'delegate-git')
        int seen = 0
        def arm = { String asked ->
            if (asked == op && ++seen == n) StandIn.repoint(link, 'fail-subcommand')
        }
        new Kill(git: new GitProcessRunner(link.toString()), port: { StageExecutor port ->
            [execute: { StageExecutor.Request request ->
                    arm('start'); port.execute(request)
                },
                poll: { StageExecutor.Request request, turn ->
                    arm('poll'); port.poll(request, turn)
                }] as StageExecutor
        })
    }
}
