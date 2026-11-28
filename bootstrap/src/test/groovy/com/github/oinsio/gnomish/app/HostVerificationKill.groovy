package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * A git-mode host round killed during its verification (design D22 of
 * migrate-verify-to-executor-contract, kill windows W0 and W2 of D21), written by the production
 * pieces of the dying instance: its host-git decoration's snapshot step over the run's cell, after a
 * stage executor that did the gnome's work, then — for W2 — its persistence's pending write, and the
 * residue the killed checks left in the worktree. The pickup is the caller's: a second instance over
 * the same clone and its real origin.
 *
 * <p>The round's one check passes only on the snapshot's tree: the gnome's file is there and the
 * killed checks' residue is not — so a resume that salvaged the residue, or verified a working copy
 * other than the snapshot, fails it.
 */
final class HostVerificationKill {

    static final String GNOME_FILE = 'gnome.txt'

    static final String RESIDUE = 'residue.txt'

    static final String CHECK = "test -e ${GNOME_FILE} && test ! -e ${RESIDUE}"

    private HostVerificationKill() {}

    /** One automatic stage per name, each verified by {@link #CHECK}: a pass of the last completes the task. */
    static PipelineDefinition pipeline(String... names) {
        new PipelineDefinition('1', new AutonomyLimits(1), names.collect { String name ->
            new StageDefinition(name, 'purpose', [], [],
            new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
            'instructions.md', [VerifyChecks.command(CHECK)], new AutonomyLimits(1), AdvancementMode.AUTO)
        })
    }

    /** The commit right above {@code below} on the task branch: the round's close over its snapshot. */
    static String closeAbove(Path repo, String taskId, String below) {
        def list = new GitProcessRunner().run(repo, 'rev-list', '--reverse', "${below}..gnomish/${taskId}".toString())
        assert list.exitCode() == 0
        list.stdout().forParsing().readLines().first()
    }

    /**
     * The dying instance's round {@code round} of {@code stage}: opened over the worktree's tip, the
     * gnome's file written, the snapshot registered by the production host snapshot step — then the
     * kill, leaving the residue of a check that was running.
     *
     * @return the run's cell, holding the round closed at its snapshot
     */
    static CurrentRound killAfterSnapshot(TaskGit git, Path worktree, String taskId, String stage, int round) {
        def cell = new CurrentRound()
        cell.open(RoundToken.of(head(worktree)))
        def gnome = { StageExecutor.Request request ->
            Files.writeString(worktree.resolve(GNOME_FILE), 'the gnome\'s work')
            ExecutionResult.completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey(taskId, stage, round), []), [])
        } as StageExecutor
        def definition = pipeline(stage).stages().first()
        git.midRoundPush().snapshotting(gnome, cell).execute(new StageExecutor.Request(
                        new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                        definition, new DirectoryWorkspace(worktree), round, []))
        Files.writeString(worktree.resolve(RESIDUE), 'what the killed check wrote')
        cell
    }

    /**
     * The pending write the dying instance's verification landed before the kill: the state the
     * snapshot registered, byte for byte (D21 item 12), with one entry of the round.
     *
     * @return the entry the pending commit carries
     */
    static PendingPollRecord landPending(TaskGit git, Path worktree, String taskId, CurrentRound cell, int round) {
        def entry = new PendingPollRecord(new PendingSubject.Check(new CheckRef(0, UntrustedText.manifest(CHECK))), round, cell.opened(),
                new PendingKey('run-1'), Instant.parse('2026-11-12T09:00:00Z'))
        def registered = stateAt(worktree, cell.closed().registered())
        git.store().attemptPersistence(worktree, taskId, cell).persistPending(taskId, registered.withPendingPolls([entry]))
        entry
    }

    /** The state at {@code commit}, through the parse the live write and every resume use. */
    static TaskState stateAt(Path repo, String commit) {
        def blob = new GitProcessRunner().run(repo, 'show', "${commit}:.gnomish-task/state.json".toString())
        assert blob.exitCode() == 0
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(blob.stdout().forParsing())))
    }

    /** The factory register of {@code taskId} in {@code repo}. */
    static String registerRef(String taskId) {
        'refs/gnomish-factory/' + TaskIdSanitizer.sanitize(taskId)
    }

    private static String head(Path worktree) {
        def rev = new GitProcessRunner().run(worktree, 'rev-parse', 'HEAD')
        assert rev.exitCode() == 0
        rev.stdout().forParsing().trim()
    }
}
