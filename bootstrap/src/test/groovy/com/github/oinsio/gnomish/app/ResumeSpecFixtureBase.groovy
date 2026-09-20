package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.slf4j.MDC
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Common fixture shared by {@link GitResumeSpecBase} and {@link TakeResumeSpecBase}: a
 * bare-repo-backed clone with an initial commit, a {@link GitProcessRunner}, and the
 * builder/helper methods both resume-runner families need to create tasks, build pipeline
 * definitions, and persist rounds. Spock composes {@code setup()}/{@code cleanup()} across the
 * inheritance hierarchy automatically, so subclasses may add their own without calling super.
 *
 * <p>Implements FR5, FR8, FR9, FR12, UX2, D3 of add-git-workflow / add-tracker-port.
 */
abstract class ResumeSpecFixtureBase extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    def gitRunner = new GitProcessRunner()

    /**
     * The production-shaped bundle every runner built here drives, and with it the one tenure
     * record this spec instance owns (FR5, design D3 of fix-claim-epoch-fence): the helper writers
     * below stamp from {@code taskGit.epochs()}, so a branch seeded by the fixture and a branch
     * written by the run under test can never carry epochs from two different records. A spec that
     * holds no claim leaves the record unfilled and its commits unstamped, exactly as plain {@code
     * gnomish run} does in production.
     */
    protected final TaskGit taskGit = TaskGitFixture.real()

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), 'build it\n')
        // FR13, D14 of add-base-ref-resolution: TakeResumeSpecBase's own fresh-claim scenarios
        // read their task tier from git objects at the resolved base commit, so this shared clone
        // carries a real, loadable pipeline matching #stage()/#pipeline() below, alongside the
        // root-level instructions.md the manual-run (GitResumeSpecBase) specs read directly.
        Files.createDirectories(cloneDir.resolve('.gnomish/stages/build'))
        Files.writeString(cloneDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(cloneDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(cloneDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: purpose
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
advancement: auto
''')
        Files.writeString(cloneDir.resolve('.gnomish/config.yaml'), '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        commitAll(cloneDir)
        worktreesRoot = tempDir.resolve('worktrees-root')
    }

    def cleanup() {
        MDC.remove('taskId')
    }

    protected GitTaskRepository repository() {
        new GitTaskRepository(gitRunner, cloneDir, worktreesRoot, taskGit.epochs())
    }

    protected Path expectedWorktree(String taskDir) {
        worktreesRoot.resolve('my-project').resolve(taskDir)
    }

    protected static StageDefinition stage(AdvancementMode mode = AdvancementMode.AUTO) {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), mode)
    }

    protected static PipelineDefinition pipeline(AdvancementMode mode = AdvancementMode.AUTO) {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage(mode)])
    }

    /** Persists one real round via GitAttemptPersistence so state.json exists, as a live task would. */
    protected void persistOneRound(String taskId, TaskState state) {
        def worktree = expectedWorktree(taskId)
        def persistence = new GitAttemptPersistence(gitRunner, worktree, taskId, taskGit.epochs())
        def trace = new ToolTrace(new AttemptKey(taskId, 'build', 0),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ])
        persistence.persist(taskId, state, trace)
    }
}
