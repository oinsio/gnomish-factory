package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.e2e.gitea.GiteaContainerFixture
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The world of a container-mode {@code gnomish run} that parks (design D8 of make-run-headless): a
 * factory clone with a Gitea repository as its real remote, the fresh run and the resume over the
 * production container support, and the readings every park spec takes — the tip's {@code task.json},
 * both replicas' tips, the box name. {@code RunParkRecordingContainerE2ESpec} drives the boundaries
 * through it; {@code RunParkKillPointContainerE2ESpec} the kill windows between them.
 *
 * <p>The concrete spec carries the Docker gate and the timeout: a world is what it reads, not when it
 * may run.
 */
abstract class ContainerParkSpecBase extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    private static final String TASK_JSON = '.gnomish-task/task.json'

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture()

    @TempDir
    Path tempDir

    Path cloneDir
    String originUrl
    String taskId

    def setupSpec() {
        gitea.start()
    }

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'container-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(cloneDir)
        originUrl = gitea.createRepository("run-park-${System.nanoTime()}")
        addRemote(cloneDir, 'origin', originUrl)
        gitExitCode(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
    }

    def cleanup() {
        if (taskId != null) {
            ContainerE2eDocker.removeTaskObjects(taskId)
        }
    }

    static List<Segment> segments(PipelineDefinition pipeline) {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), pipeline.stages())
        ]
    }

    static SandboxProperties sandbox() {
        new SandboxProperties(FakeAgentSandboxImage.ensureBuilt('plain-round'), null, null, null, [], [], false, null, null, null, null)
    }

    /**
     * A fresh container run of {@code pipeline} for {@link #taskId}, dying at {@code killPoint} of its
     * park when one is named — otherwise the real boundary runs to its exit.
     */
    void freshRun(PipelineDefinition pipeline, RunKillPoint killPoint = null) {
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def git = TaskGitFixture.real()
        def support = ContainerSupportFixture.real(git.epochs())
        new ContainerGitModeRunner(newAssembly(factoryProps), git, sandbox(), factoryProps,
                killPoint == null ? support : RunKills.killedAt(killPoint, support), LiveConsoleIO.onStdout())
                .run(new RunOrder(cloneDir, null, pipeline, false), segments(pipeline),
                new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                TaskState.atStageStart('work'))
    }

    /** The container resume of {@link #taskId} through the real runner chain. */
    void resume(PipelineDefinition pipeline) {
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def git = TaskGitFixture.real()
        new ContainerResumeRunner(newAssembly(factoryProps), git, sandbox(), factoryProps, 'taskId',
                ContainerSupportFixture.real(git.epochs()))
                .run(new RunOrder(cloneDir, null, pipeline, false), taskId, null, segments(pipeline))
    }

    String branch() {
        "gnomish/${taskId}"
    }

    String boxName() {
        "gnomish-box-${taskId}"
    }

    /** The factory clone's tip {@code task.json}. */
    TaskJsonDto tipTask() {
        TaskJsonMapper.readDto(UntrustedText.branchDocument(gitOutput(cloneDir, 'show', "${branch()}:${TASK_JSON}")))
    }

    String localTip() {
        gitOutput(cloneDir, 'rev-parse', branch())
    }

    /** What the real remote holds for the task branch, read through a fresh clone of it. */
    String originTip() {
        def probe = tempDir.resolve("origin-${System.nanoTime()}")
        seedClone(tempDir, originUrl, probe)
        fetchFromOrigin(probe, "refs/heads/${branch()}:refs/remotes/origin/${branch()}")
        gitOutput(probe, 'rev-parse', "origin/${branch()}")
    }
}
