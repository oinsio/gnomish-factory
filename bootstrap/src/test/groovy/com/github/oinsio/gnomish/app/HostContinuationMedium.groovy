package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.AdversarialLocalConfig
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import java.nio.file.Files
import java.nio.file.Path

/**
 * The host worktree medium of the identity specs (task 5.3 of make-checkpoint-gate-durable): a
 * registered factory clone with a bare origin, parked by {@link GitModeRunner} — {@code gnomish run}
 * — and continued by {@link GitResumeRunner} — {@code gnomish run --resume} — each assembled the way
 * the run-mode specs assemble them ({@code RunParkKillPointSpec}), over the task-git bundle with its
 * tenure record ({@link TaskGitFixture#real()}).
 */
class HostContinuationMedium implements ContinuationMedium, BareGitRepoFixture, AppAssemblyFixture {

    private final Path cloneDir
    private final Path origin
    private final RegisteredClone registeredClone

    HostContinuationMedium(Path root) {
        Files.createDirectories(root)
        cloneDir = initWorkingRepo(root, 'my-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(cloneDir)
        origin = initBareRepo(root, 'origin.git')
        addRemote(cloneDir, 'origin', origin.toString())
        gitOutput(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        registeredClone = RegisteredCloneFixture.registered(root.resolve('home'), cloneDir)
    }

    /**
     * A second instance over the same {@code origin}: its own fresh clone of it, under the adversarial
     * local configuration every fixture repository carries, registered in its own home — another
     * machine, as far as the task is concerned (NFR-R2 of extract-agent-round-runner; the shape of
     * {@code DeliveredCleanupWorld#secondClone}).
     */
    HostContinuationMedium(Path root, Path origin) {
        Files.createDirectories(root)
        cloneDir = seedClone(root, origin.toString(), root.resolve('my-project'), '--branch', 'main')
        AdversarialLocalConfig.plant(cloneDir)
        this.origin = origin
        registeredClone = RegisteredCloneFixture.registered(root.resolve('home'), cloneDir)
    }

    @Override
    String name() {
        'host'
    }

    @Override
    Path origin() {
        origin
    }

    @Override
    void park(String taskId, PipelineDefinition definition, String scenario) {
        RunParkedException parked = null
        try {
            new GitModeRunner(assembly(scenario), TaskGitFixture.real(), registeredClone, LiveConsoleIO.onStdout())
                    .run(order(definition), context(taskId), TaskState.atStageStart(definition.stages().first().name()))
        } catch (RunParkedException e) {
            parked = e
        }
        assert parked != null: "the run of ${taskId} did not park"
    }

    /** The registered factory clone the run and the resume work in; {@code status} reads it too. */
    RegisteredClone registeredClone() {
        registeredClone
    }

    @Override
    void plantStaleRequest(String taskId) {
        Path worktree = registeredClone.worktrees().resolve(taskId)
        Path request = worktree.resolve(BranchHistory.STALE_REQUEST)
        Files.createDirectories(request.parent)
        Files.writeString(request, '{"question":"left over?","options":[]}')
        gitOutput(worktree, 'add', '-f', '--', BranchHistory.STALE_REQUEST)
        gitOutput(worktree, 'commit', '-q', '-m', 'a request left on the tip')
        String branch = TaskIdSanitizer.branchName(taskId)
        gitOutput(cloneDir, 'push', 'origin', "refs/heads/${branch}:refs/heads/${branch}")
    }

    @Override
    void resume(String taskId, PipelineDefinition definition, String scenario, String decision) {
        resume(taskId, definition, scenario, decision, TaskGitFixture.real())
    }

    /** {@link #resume} over {@code git} — a wrapped bundle, for a spec that kills the continuation. */
    void resume(String taskId, PipelineDefinition definition, String scenario, String decision, TaskGit git) {
        resume(taskId, definition, FakeAgentSupport.propertiesFor(scenario), decision, git)
    }

    /** {@link #resume} with the agent {@code agent} names — a capturing binary, for a spec that reads the round. */
    void resume(String taskId, PipelineDefinition definition, FactoryProperties agent, String decision, TaskGit git) {
        try {
            new GitResumeRunner(assembly(agent), git, registeredClone, 'taskId')
                    .run(order(definition), taskId, decision)
        } catch (RunParkedException ignored) {
            // The continued drive parked again; the continuation commit is already on origin.
        }
    }

    private RunOrder order(PipelineDefinition definition) {
        new RunOrder(cloneDir, null, definition, false)
    }

    private ManualRunAssembly assembly(String scenario) {
        assembly(FakeAgentSupport.propertiesFor(scenario))
    }

    private ManualRunAssembly assembly(FactoryProperties agent) {
        newAssembly(new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8'), agent)
    }
}
