package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskBranches
import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import java.nio.file.Path

/**
 * One task under a kill-point run: the branch medium its transition writes to, the tracker its
 * external effect lands on, and the two readings the harness compares — the classified shape and
 * the durable fingerprint.
 *
 * <p>Medium-agnostic on purpose (FR2, M1 of harden-task-branch-contract): the host worktree
 * repository and the container bare-objects repository are two {@link TaskLifecycleStore}
 * implementations of the same write protocol, so the same transition table runs against both and
 * the pair they are declared as (`.claude/rules/manual-sync-pairs.md`) is checked rather than
 * assumed.
 */
class KillPointWorld implements BareGitRepoFixture {

    private static final String TASK_JSON = '.gnomish-task/task.json'

    /** The repository shapes are classified in: the factory clone (host) or the bare repo (box). */
    Path repoDir

    /** The lifecycle writer under test — the medium's own realization of the write protocol. */
    TaskLifecycleStore store

    String taskId

    TaskRef ref

    InstanceId instanceId

    InMemoryTracker tracker

    InMemoryTrackerHarness trackerHarness

    /** The classified shape's label, read through the production classifier over the real tip. */
    String shape() {
        new GitTaskBranches(new GitProcessRunner()).classifyShape(repoDir, taskId).label()
    }

    /** The tip's {@code task.json}, or {@code null} once the cleanup commit removed the envelope. */
    TaskJsonDto tipTask() {
        String blob = "gnomish/${taskId}:${TASK_JSON}"
        gitExitCode(repoDir, 'cat-file', '-e', blob) == 0
                ? TaskJsonMapper.readDto(gitOutput(repoDir, 'show', blob))
                : null
    }

    /**
     * Everything a second recovery pass must leave untouched: the branch's non-service commit
     * subjects, the tip's recorded outcome and pending marker, the decisions it carries, the
     * tracker state the effect landed on, and the replies still pending there.
     *
     * <p>Service commits are excluded deliberately — a conservatively classified interrupt may cost
     * one re-run service commit and never paid work (design D13, NFR-C1).
     */
    Map fingerprint() {
        def tip = tipTask()
        [
            commits: substantiveSubjects(),
            outcome: tip?.outcome()?.toString(),
            pending: tip?.trackerWritePending(),
            decisions: tip?.decisions()?.collect { it.body() },
            tracker: tracker.fetchTask(ref).state().toString(),
            replies: tracker.collectDecisions(ref).collect { it.body() },
        ]
    }

    private List<String> substantiveSubjects() {
        def service = [
            ServiceCommitMessages.trackerWriteConfirmed(),
            ServiceCommitMessages.salvage()
        ]
        gitOutput(repoDir, 'log', '--format=%s', "gnomish/${taskId}").readLines()
                .findAll { !service.contains(it) }
    }
}
