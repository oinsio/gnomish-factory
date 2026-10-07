package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.e2e.gitea.GiteaContainerFixture
import com.github.oinsio.gnomish.e2e.gitea.GiteaTaskSeedFixture
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * NFR-R3, U2, G2 of add-git-workflow (task 6.6): "for other instances, unpushed work does not
 * exist — resume semantics rely only on what reached origin." Two separate local clones stand in
 * for two separate factory instances (U2's "second instance / another machine" scenario), both
 * pointed at the same Gitea repo as {@code origin} but sharing no local git state whatsoever:
 * instance A creates the task and completes a round, pushing it via {@link
 * com.github.oinsio.gnomish.adapter.git.BestEffortPush}; instance B is a fresh {@code git clone}
 * of the Gitea repo made only after A's push, so it can only see the task branch by locating it
 * on {@code origin} (FR8, {@link com.github.oinsio.gnomish.adapter.git.TaskBranchLocator}).
 *
 * <p>Reuses {@link GiteaContainerFixture} exactly as bootstrapped by task 6.5's harness spec.
 *
 * <p>Implements NFR-R3 of add-git-workflow.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unreachable — see GiteaAvailability; Docker is a dev/CI prerequisite for the Gitea E2E layer (.claude/rules/testing.md)')
class GiteaCrossInstanceResumeE2ESpec extends Specification implements GiteaTaskSeedFixture, AppAssemblyFixture {

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture()

    @TempDir
    Path tempDir

    // Wired per feature, so it gets its own repository — see GiteaContainerFixture's sharing rule.
    String originUrl

    def setupSpec() {
        gitea.start()
    }

    def setup() {
        originUrl = gitea.createRepository("cross-instance-${System.nanoTime()}")
    }

    private static StageDefinition stage(String name, AdvancementMode advancement) {
        new StageDefinition(
                name, 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), advancement)
    }

    /** Two stages: instance A's own round only completes stage "build", never the whole task —
     * "build" ends at a manual checkpoint, where A's operator leaves — so the run stops mid-task
     * with no Completed/cleanup commit of its own (that commit is not pushed, per design D11's
     * push scope of "after every round"); instance B then resumes and finishes stage "verify" to
     * completion, producing its own round push. */
    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            stage('build', AdvancementMode.MANUAL),
            stage('verify', AdvancementMode.AUTO)
        ])
    }

    /** An assembly whose gnome is the fake agent playing {@code plain-round} (FR6 of
     * remove-interactive-console), reading the operator dialogs from {@code input}. */
    private ManualRunAssembly assembly(InputStream input = new ByteArrayInputStream(new byte[0])) {
        newAssembly(input, System.out, FakeAgentSupport.propertiesFor('plain-round'))
    }

    /** A brand-new, independent local clone of the Gitea repo — stands in for a separate machine. */
    private Path freshClone(String name) {
        Path dir = tempDir.resolve(name)
        seedClone(tempDir, originUrl, dir)
        dir
    }

    // NFR-R3, U2: instance B never shares local git state with instance A — it only clones the
    // Gitea repo AFTER A's push — yet it resumes the same task and sees A's pushed round; its own
    // further round also lands on the shared origin, provable via a third, independent clone.
    def "a second instance, in a fresh clone with no local knowledge of the first, resumes and continues the task from origin"() {
        given: 'instance A: a clone with a project history, seeded on origin'
        def instanceA = freshClone('instance-a')
        seedAndPushGnomishTask(instanceA)
        def cloneA = RegisteredCloneFixture.registered(tempDir.resolve('home-a'), instanceA)
        def taskId = 'CROSS-1'

        when: 'instance A completes only the first stage\'s round and is killed at the manual checkpoint "build" ends at, before the park is recorded: "verify" never starts'
        new GitModeRunner(assembly(), RunKills.killedBeforeParkRecord(TaskGitFixture.real()), cloneA, LiveConsoleIO.onStdout())
                .run(new RunOrder(instanceA, null, pipeline(), false),
                context(taskId), TaskState.atStageStart('build'))

        then: 'the kill ended the run — the task stopped mid-run, with only the first round durably committed'
        thrown(RunKills.SimulatedKill)
        def tipAfterA = gitOutput(instanceA, 'rev-parse', "gnomish/${taskId}")
        tipAfterA

        when: 'instance B: a completely separate fresh clone, made only now — after A already pushed'
        def instanceB = freshClone('instance-b')
        def cloneB = RegisteredCloneFixture.registered(tempDir.resolve('home-b'), instanceB)

        and: 'instance B resumes the same task purely via what reached origin (FR8/D9 locate -> fetch)'
        def bundle = new GitResumeRunner(assembly(), TaskGitFixture.real(), cloneB, 'taskId').bootstrap(instanceB, taskId)

        then: 'instance B sees exactly the round instance A pushed, without ever touching instance A locally'
        Files.exists(bundle.worktreePath().resolve('.gnomish-task').resolve('task.json'))
        gitOutput(instanceB, 'rev-parse', "gnomish/${taskId}") == tipAfterA

        when: 'instance B continues the task to completion, driving the second round ("verify") and pushing it'
        new GitResumeRunner(assembly(), TaskGitFixture.real(), cloneB, 'taskId')
                .run(new RunOrder(instanceB, null, pipeline(), false), taskId, null)

        then: 'instance B\'s own round commit for "verify" exists, distinct from instance A\'s "build" round'
        def verifyRoundSha = roundCommitSha(instanceB, taskId, 'verify')
        verifyRoundSha
        verifyRoundSha != tipAfterA

        and: 'a third, wholly independent clone confirms that round reached origin — proof this is not local-only (FR11 push scope is the round commit; the final Completed/cleanup commit is a separate, unpushed lifecycle write)'
        def verifyClone = freshClone('verify-clone')
        roundReachedOrigin(verifyClone, taskId, verifyRoundSha)
    }
}
