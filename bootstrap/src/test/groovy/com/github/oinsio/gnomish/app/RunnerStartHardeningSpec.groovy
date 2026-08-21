package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR17 of add-sandbox-core (design D11): runner-start hygiene — every entry path that starts
 * operating on a factory-managed clone neutralizes git hooks first, pointing the clone's {@code
 * core.hooksPath} at the factory-owned empty directory, before any worktree or branch work. One
 * capability, five call sites: the fresh host run, the fresh container run, the container resume,
 * the take resume bootstrap, and the fresh take claim. Each is stopped right after hardening by a
 * cheap usage refusal, so the config write is the observable proof.
 */
class RunnerStartHardeningSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    def gitRunner = new GitProcessRunner()
    Path worktreesRoot

    def setup() {
        worktreesRoot = tempDir.resolve('worktrees-root')
    }

    private Path freshClone(String name) {
        Path clone = initWorkingRepo(tempDir, name)
        Files.writeString(clone.resolve('instructions.md'), 'build it\n')
        gitRunner.run(clone, 'add', 'instructions.md')
        gitRunner.run(clone, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        clone
    }

    private String hooksPath(Path clone) {
        gitRunner.run(clone, 'config', 'core.hooksPath').stdout().trim()
    }

    private String expectedHooksPath(Path clone) {
        gitRunner.run(clone, 'rev-parse', '--absolute-git-dir').stdout().trim() + '/gnomish-empty-hooks'
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    private static TaskContext context(String taskId) {
        new TaskContext(taskId, 'title', 'body', List.<Decision> of())
    }

    private static SandboxProperties sandboxProperties() {
        new SandboxProperties('gnomish/img', null, null, null, [], [], false, null, null, null, null)
    }

    // FR17: the fresh host git run hardens the clone before creating branch and worktree.
    def "GitModeRunner hardens the factory clone's hooks path at run start"() {
        given: 'a clone whose task branch already exists, refusing the run right after hardening'
        Path clone = freshClone('host-fresh')
        gitRunner.run(clone, 'branch', 'gnomish/H-1', 'HEAD')
        def runner = new GitModeRunner(newAssembly(), TaskGitFixture.real(), worktreesRoot)

        when:
        runner.run(clone, null, pipeline(), context('H-1'), TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.ALL)

        then:
        thrown(UsageException)
        hooksPath(clone) == expectedHooksPath(clone)
    }

    // FR17: the fresh container run hardens the clone before the bare-object branch creation.
    def "ContainerGitModeRunner hardens the factory clone's hooks path at run start"() {
        given:
        Path clone = freshClone('container-fresh')
        gitRunner.run(clone, 'branch', 'gnomish/C-1', 'HEAD')
        def runner = new ContainerGitModeRunner(
                newAssembly(), TaskGitFixture.real(), sandboxProperties(), testProperties(), ContainerSupportFixture.real())
        def segments = [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ]

        when:
        runner.run(clone, null, pipeline(), segments, context('C-1'), TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.ALL)

        then:
        thrown(UsageException)
        hooksPath(clone) == expectedHooksPath(clone)
    }

    // FR17: the container resume hardens the clone before locating the task branch.
    def "ContainerResumeRunner hardens the factory clone's hooks path at resume start"() {
        given: 'no branch for the resumed task, refusing the resume right after hardening'
        Path clone = freshClone('container-resume')
        def runner = new ContainerResumeRunner(newAssembly(), TaskGitFixture.real(), sandboxProperties(), testProperties(), 'taskId',
                ContainerSupportFixture.real())
        def segments = [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ]

        when:
        runner.run(clone, 'absent-task', pipeline(), segments, RunArguments.InteractiveMode.ALL, false)

        then:
        thrown(UsageException)
        hooksPath(clone) == expectedHooksPath(clone)
    }

    // FR17: the take resume bootstrap hardens the clone before the worktree materializes.
    def "TakeResumeBootstrap hardens the factory clone's hooks path before locating the branch"() {
        given:
        Path clone = freshClone('take-resume')

        when:
        new TakeResumeBootstrap(TaskGitFixture.real(), worktreesRoot, 'taskId').bootstrap(clone, 'absent-task')

        then:
        thrown(UsageException)
        hooksPath(clone) == expectedHooksPath(clone)
    }

    // FR17: the fresh take claim hardens the clone before creating branch and worktree.
    def "TakeFreshClaim hardens the factory clone's hooks path before creating the task branch"() {
        given: 'a clone whose task branch already exists, refusing the claim right after hardening'
        Path clone = freshClone('take-fresh')
        gitRunner.run(clone, 'branch', 'gnomish/T-1', 'HEAD')
        def tracker = Mock(Tracker)
        def trackerTask = new TrackerTask(
                new TaskRef('T-1'), new TaskSnapshot('T-1', 'title', 'body'),
                new TrackerTaskState.Ready(), AbortFacts.none(), false)

        when:
        TakeFreshClaim.claim(
                newAssembly(), TaskGitFixture.real(), worktreesRoot, new AbortHandler(tracker, Clock.systemUTC()), 3, [],
                clone, null, pipeline(), RunArguments.InteractiveMode.ALL,
                trackerTask, tracker, InstanceId.generate('test-instance'), new ClaimLossFlag())

        then:
        thrown(UsageException)
        hooksPath(clone) == expectedHooksPath(clone)
    }
}
