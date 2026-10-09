package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path

/**
 * One task branch's history on the durable medium (a bare origin), read commit by commit through
 * the production wire readers ({@link TaskJsonMapper}, {@link StateJsonMapper}) — the reading side
 * of the identity specs of make-checkpoint-gate-durable (task 5.3), which judge every tip, not
 * only the last one.
 */
class BranchHistory implements BareGitRepoFixture {

    static final String TASK_JSON = '.gnomish-task/task.json'
    static final String STATE_JSON = '.gnomish-task/state.json'
    static final String DECISIONS = '.gnomish-task/decisions/'

    /** A request no current round's token names: what a tip may carry over from an earlier round. */
    static final String STALE_REQUEST = DECISIONS + 'build-a0-' + 'f' * 40 + '.json'

    final Path repo
    final String ref

    BranchHistory(Path repo, String taskId) {
        this.repo = repo
        this.ref = 'refs/heads/' + TaskIdSanitizer.branchName(taskId)
    }

    String tip() {
        gitOutput(repo, 'rev-parse', ref)
    }

    /** Every commit of the branch, oldest first, along its first-parent line. */
    List<String> commits() {
        gitOutput(repo, 'rev-list', '--reverse', '--first-parent', ref).readLines()
    }

    /** The commits after {@code commit} on the branch, oldest first. */
    List<String> after(String commit) {
        gitOutput(repo, 'rev-list', '--reverse', '--first-parent', "${commit}..${ref}").readLines()
    }

    String parent(String commit) {
        gitOutput(repo, 'rev-parse', commit + '^')
    }

    String subject(String commit) {
        gitOutput(repo, 'log', '-1', '--format=%s', commit)
    }

    /** True when {@code ancestor} is {@code commit} itself or one of its ancestors. */
    boolean descends(String commit, String ancestor) {
        gitExitCode(repo, 'merge-base', '--is-ancestor', ancestor, commit) == 0
    }

    /** The commit's {@code state.json}, or {@code null} where the envelope is absent. */
    TaskState state(String commit) {
        String json = blob(commit, STATE_JSON)
        json == null ? null : StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json)))
    }

    Position position(String commit) {
        state(commit)?.position()
    }

    /** The commit's recorded outcome, or {@code null} when none is recorded (or no envelope). */
    TaskOutcomeDto outcome(String commit) {
        task(commit)?.outcome()
    }

    List<String> decisionBodies(String commit) {
        task(commit)?.decisions()?.collect { it.body() }
    }

    /** Every path under {@code .gnomish-task/decisions/} in the commit's tree. */
    List<String> requests(String commit) {
        gitOutput(repo, 'ls-tree', '-r', '--name-only', commit, '--', DECISIONS).readLines()
    }

    /**
     * The position that follows {@code stage} in {@code definition}, spelled here from the pipeline's
     * stage order and nothing else: the repositories no longer check the approved position, so the
     * identity spec does, independently of the production owner ({@code Advancement.afterGate}).
     */
    static Position following(PipelineDefinition definition, String stage) {
        def names = definition.stages()*.name()
        int index = names.indexOf(stage)
        assert index >= 0: "${stage} is not declared in the pinned definition"
        index + 1 <names.size() ? new Position.AtStage(names[index + 1]) : new Position.PipelineEnd()
    }

    private TaskJsonDto task(String commit) {
        String json = blob(commit, TASK_JSON)
        json == null ? null : TaskJsonMapper.readDto(UntrustedText.branchDocument(json))
    }

    private String blob(String commit, String path) {
        gitExitCode(repo, 'cat-file', '-e', "${commit}:${path}") == 0 ? gitOutput(repo, 'show', "${commit}:${path}") : null
    }
}
