package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
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
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6 of own-git-invocation-policy (design D4), over FR17 of add-sandbox-core (design D11) and
 * NFR-S2 of tighten-spec-contract: every entry path that starts operating on a factory-managed
 * clone runs the ref-backend check first, ahead of its first write, and none of them writes the
 * clone's configuration. Five call sites: the fresh host run, the fresh container run, the
 * container resume, the take resume bootstrap, and the fresh take claim. Each path is set up so a
 * cheap usage refusal stops it on a reftable clone; on the same clone migrated to the files
 * backend the check refuses it instead, before that refusal and before any ref, branch or
 * worktree-registry write. The clone's {@code .git/config} is byte-identical either way.
 */
class RunnerStartHardeningSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    // FR6: the check still runs on every path; a passing clone is stopped later, its config untouched.
    // Scenario: git/git-task-persistence / The clone's configuration is not written
    def "#path writes no configuration to a reftable clone it starts on"() {
        given: 'a reftable clone set up so the path stops at its usage refusal'
        Path clone = prepare(path)
        byte[] config = configBytes(clone)

        when:
        start(path, clone)

        then:
        thrown(UsageException)
        configBytes(clone) == config

        where:
        path << PATHS
    }

    // FR6, NFR-S2: the check runs before the first write — a refused clone gets no ref, branch,
    // worktree-registry or configuration write, and the usage refusal behind it is never reached.
    def "#path refuses a files-backend clone before its first write"() {
        given: 'the same clone, migrated to the files backend'
        Path clone = prepare(path)
        gitOutput(clone, 'refs', 'migrate', '--ref-format=files')
        byte[] config = configBytes(clone)
        String refs = gitOutput(clone, 'for-each-ref')
        String worktrees = gitOutput(clone, 'worktree', 'list', '--porcelain')

        when:
        start(path, clone)

        then:
        thrown(CloneRefFormatRefusedException)
        configBytes(clone) == config
        gitOutput(clone, 'for-each-ref') == refs
        gitOutput(clone, 'worktree', 'list', '--porcelain') == worktrees

        where:
        path << PATHS
    }

    /** The five entry paths, by the class that starts each. */
    private static final List<String> PATHS =
    'GitModeRunner ContainerGitModeRunner ContainerResumeRunner TakeResumeBootstrap TakeFreshClaim'.tokenize()

    /** A clone each path refuses by usage once past the check: an existing branch, or none to resume. */
    private Path prepare(String path) {
        Path clone = initWorkingRepo(tempDir, 'clone')
        Files.createDirectories(clone.resolve('.gnomish'))
        commit(clone, '.gnomish/instructions.md', 'build it\n')
        switch (path) {
            case 'GitModeRunner': gitOutput(clone, 'branch', 'gnomish/H-1', 'HEAD'); break
            case 'ContainerGitModeRunner': gitOutput(clone, 'branch', 'gnomish/C-1', 'HEAD'); break
            case 'TakeFreshClaim': prepareTakeFresh(clone); break
        }
        clone
    }

    private void start(String path, Path clone) {
        def order = new RunOrder(clone, null, pipeline(), false)
        def props = FakeAgentSupport.propertiesFor('plain-round')
        def git = TaskGitFixture.real()
        switch (path) {
            case 'GitModeRunner':
                new GitModeRunner(newAssembly(props), git, registered(clone), LiveConsoleIO.onStdout())
                .run(order, context('H-1'), TaskState.atStageStart('build'))
                break
            case 'ContainerGitModeRunner':
                new ContainerGitModeRunner(newAssembly(props), git,
                ContainerSupportFixture.real(git.epochs(), sandboxProperties(), props), LiveConsoleIO.onStdout())
                .run(order, segments(), context('C-1'), TaskState.atStageStart('build'))
                break
            case 'ContainerResumeRunner':
                new ContainerResumeRunner(newAssembly(props), git, 'taskId',
                ContainerSupportFixture.real(git.epochs(), sandboxProperties(), props))
                .run(order, 'absent-task', null, segments())
                break
            case 'TakeResumeBootstrap':
                new TakeResumeBootstrap(git, registered(clone), 'taskId').bootstrap(clone, 'absent-task')
                break
            case 'TakeFreshClaim':
                startTakeFresh(clone, order, git)
                break
        }
    }

    /** An existing branch, a loadable task tier at the base and a real origin (FR2, FR13, D14 of add-base-ref-resolution). */
    private void prepareTakeFresh(Path clone) {
        gitOutput(clone, 'branch', 'gnomish/T-1', 'HEAD')
        Files.writeString(clone.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(clone.resolve('.gnomish/stages/build/instructions.md').tap {
            Files.createDirectories(it.parent)
        }, 'build it\n')
        Files.writeString(clone.resolve('.gnomish/stages/build/stage.yaml'), '{purpose: build it, executor: '
                + '{type: agent-cli, model: model-x}, instructions: stages/build/instructions.md, advancement: auto}\n')
        Files.writeString(clone.resolve('.gnomish/config.yaml'), '{schemaVersion: "1", autonomy: {attemptLimit: 3}, '
                + 'tracker: {type: github, github: {api-url: "https://api.github.com", repo: acme/widgets}}}\n')
        commitAll(clone, 'pipeline')
        addOrigin(clone, tempDir)
    }

    private void startTakeFresh(Path clone, RunOrder order, TaskGit git) {
        def tracker = Mock(Tracker)
        def trackerTask = new TrackerTask(
                new TaskRef('T-1'), new TaskSnapshot('T-1', UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(), AbortFacts.none(), false)
        def assembly = newAssembly(FakeAgentSupport.propertiesFor('plain-round'), VirtualTimeEquipment.create())
        new TakeFreshClaim(new SlotWiring(
                        assembly, git, registered(clone), 'taskId',
                        new AbortFuse(new AbortHandler(tracker, assembly.timeEquipment().clock()), 3), [], ContainerTakeSupportFixture.hostOnly(),
                        new ClaimTenure(ClaimBeat.NONE, new ClaimLossFlag()),
                        new TrustedBaseContext(BaseDefinition.none(), new DefaultBranch(currentBranch(clone))))).claim(
                new TakeOrder(order, trackerTask, tracker, InstanceId.generate('test-instance')))
    }

    /** {@code clone} as the loader would resolve it, registered through the production registry. */
    private RegisteredClone registered(Path clone) {
        RegisteredCloneFixture.registered(tempDir.resolve('home'), clone)
    }

    private static byte[] configBytes(Path clone) {
        Files.readAllBytes(clone.resolve('.git/config'))
    }

    private static List<Segment> segments() {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ]
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

    private static SandboxProperties sandboxProperties() {
        new SandboxProperties('gnomish/img', null, null, null, [], [], false, null, null, null, null)
    }
}
