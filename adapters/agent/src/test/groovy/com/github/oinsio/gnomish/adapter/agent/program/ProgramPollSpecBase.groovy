package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.adapter.git.AdversarialLocalConfig
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.gitobjects.LocalGitRepoFixture
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The real host path the poll specs of {@link ProgramExecutor} share (FR23, FR11, NFR-S4 of
 * define-executor-contract): the program is the stand-in reference executor, which returns {@code
 * pending} with the key {@code reference-deferred} when its settings ask {@code defer}, answers a poll
 * whose {@code GNOMISH_PENDING_KEY} carries that key, and refuses any other key. The working copy is a
 * real git repository with one commit, carrying the adversarial local configuration, so a view is git's
 * own. Every launch is counted — the program and each git view alike — so a refused poll is seen to
 * spawn none. The spec is one factory run: its executors share the run's table of pending runs ({@link
 * #runs}); a resuming instance is a new run, with a table of its own ({@link #resumed}).
 *
 * <p>Test fixture base; not production code, never PIT-mutated.
 */
abstract class ProgramPollSpecBase extends Specification implements LocalGitRepoFixture {

    @TempDir
    Path tempDir

    @TempDir
    Path stderrParent

    Path repo

    HostTaskExecutionEnvironment environment

    AtomicInteger launches = new AtomicInteger()

    /** The table of pending runs of this spec's run. */
    PendingRuns runs = new PendingRuns()

    def setup() {
        repo = initWorkingRepo(tempDir)
        Files.writeString(repo.resolve('tracked.txt'), 'tracked\n')
        commitAll(repo)
        AdversarialLocalConfig.plant(repo)
        environment = environmentAt(repo)
    }

    def cleanup() {
        environment.dispose()
    }

    /** A materialized host environment over {@code root}, its launches counted. */
    protected HostTaskExecutionEnvironment environmentAt(Path root) {
        def counted = { ProcessBuilder builder, StdinSource stdin ->
            launches.incrementAndGet()
            ProcessLaunch.grouped().start(builder, stdin)
        } as ProcessLaunch
        def built = new HostTaskExecutionEnvironment(root, new VirtualClock(), TestChildEnvironment.hostAllowlist(), counted)
        built.materialize('gnomish/TASK-7', null)
        built
    }

    /** A new binding of the reference executor in {@code where}, over {@code scope}: by default this run's. */
    protected ProgramExecutor executor(List<Capability> capabilities = [Capability.POLL], def where = environment,
            PendingScope scope = PendingScope.unscoped(runs), LastRegisteredCommit registered = LastRegisteredCommit.NONE) {
        new ProgramExecutor(ProgramFixtures.declaration([
            StandIn.executor('reference').toString()
        ]),
        ProgramFixtures.describe(capabilities), [], where,
        ProgramFixtures.equipment(stderrParent, ProgramBounds.DEFAULTS, VirtualTimeEquipment.create(), registered), scope)
    }

    /**
     * The binding of a new run — another factory instance, or this one after a restart — whose
     * working copy the factory put on {@code expectedHead}: by default the repository's {@code HEAD}.
     */
    protected ProgramExecutor resumed(String expectedHead = gitOutput(repo, 'rev-parse', 'HEAD'), def where = environment) {
        executor([Capability.POLL], where, new PendingScope(new PendingRuns(), null, expectedHead))
    }

    /** A request that makes the reference answer its start with {@code pending}. */
    protected static ExecutorRequest deferred(Role role) {
        def inputs = role == Role.VERDICT ? RoleInputs.verdict('Every method is documented.') : RoleInputs.transform('build it')
        Policy policy = role == Role.VERDICT ? new Policy.ReadOnly() : new Policy.WorkspaceWrite()
        new ExecutorRequest(role, new Workspace('.'), policy, inputs, ExecutorContractJson.settingsTree([defer: true]),
        ProgramBounds.DEFAULTS.hints(), [], null)
    }
}
