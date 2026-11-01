package com.github.oinsio.gnomish.adapter.agent.program

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.adapter.git.AdversarialLocalConfig
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.gitobjects.LocalGitRepoFixture
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The working-copy-unchanged check of {@link ProgramExecutor} on the real host path (FR11, NFR-S4 of
 * define-executor-contract; design D6): a real git working copy carrying the adversarial local
 * configuration, a real process — the stand-in preset {@code edit-as}, whose per-run link names the
 * change it makes before it answers {@code completed} — and git's own view taken through the
 * environment before and after the run.
 *
 * <p>No use site in this change sends a program a {@code verdict} or {@code decision} request (the
 * stage sends {@code transform}; no verify check names an executor until
 * {@code migrate-verify-to-executor-contract}), so the requests are built here, at the adapter.
 *
 * <p>The repository is built by the module-neutral {@link LocalGitRepoFixture}: {@code :adapters:agent}
 * may not reach the sibling adapter {@code :adapters:git} that {@code BareGitRepoFixture} drives
 * (scenario build-system/module-layering "Sibling isolation holds after the vertical split"). The
 * first commit lands before {@link AdversarialLocalConfig} plants its refusing hooks, which would
 * refuse a commit made outside the factory's git policy.
 */
class ProgramWorkingCopyCheckSpec extends Specification implements LocalGitRepoFixture {

    @TempDir
    Path tempDir

    @TempDir
    Path stderrParent

    @TempDir
    Path links

    Path repo

    HostTaskExecutionEnvironment environment

    def setup() {
        repo = initWorkingRepo(tempDir)
        Files.writeString(repo.resolve('tracked.txt'), 'tracked\n')
        commitAll(repo)
        AdversarialLocalConfig.plant(repo)
        environment = new HostTaskExecutionEnvironment(
                repo, new VirtualClock(), TestChildEnvironment.hostAllowlist(), ProcessLaunch.grouped())
        environment.materialize('gnomish/TASK-7', null)
    }

    def cleanup() {
        environment.dispose()
    }

    private static ExecutorRequest request(Role role, Policy policy = new Policy.ReadOnly()) {
        def inputs = [(Role.VERDICT): RoleInputs.verdict('the criteria'),
            (Role.DECISION): RoleInputs.decision('which one?', ['a', 'b']),
            (Role.TRANSFORM): RoleInputs.transform('build it')][role]
        new ExecutorRequest(role, new Workspace('.'), policy, inputs, JsonNodeFactory.instance.objectNode(),
                ProgramBounds.DEFAULTS.hints(), [], null)
    }

    private ExecutorResult start(String edit, ExecutorRequest request) {
        Path link = StandIn.link(Files.createDirectories(links.resolve("run-${UUID.randomUUID()}")).resolve(edit), 'edit-as')
        new ProgramExecutor(ProgramFixtures.declaration([link.toString()]), ProgramFixtures.describe(), [],
        environment, ProgramFixtures.equipment(stderrParent)).start(request)
    }

    // Scenario: executor/wire-contract / Judge that wrote a file is refused
    // FR11, NFR-S4: a verdict run that modified a tracked file is refused, its answer discarded, naming path and policy
    def "NFR-S4: a verdict run that modified a file is failed/infrastructure naming the path and the policy"() {
        when:
        def result = start('modify', request(Role.VERDICT))

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.answer() == null
        result.reason().contains("program executor 'acme': a verdict run must leave the working copy unchanged "
                + '(policy readOnly); the run changed tracked.txt')

        and: 'the tokens it spent are still its usage'
        result.usage().provenance() == UsageProvenance.REPORTED

        and: 'the view ran no program the working copy configuration planted'
        AdversarialLocalConfig.assertNothingRan(repo)
    }

    // NFR-S4: each kind of change git sees is a refusal naming what changed
    def "NFR-S4: a #edit by a verdict run is refused, naming #named"() {
        when:
        def result = start(edit, request(Role.VERDICT))

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('the run changed ' + named)

        where:
        edit || named
        'untracked' || 'judge-notes.txt'
        'chmod' || 'tracked.txt'
        'rename' || 'moved.txt, tracked.txt'
        'commit' || 'branch.oid '
    }

    // NFR-S4: a refusal names the first paths and counts the rest
    def "NFR-S4: a verdict run that created #count files is refused naming #named"() {
        when:
        def result = start("files-${count}", request(Role.VERDICT))

        then:
        result.reason().forLog().endsWith('the run changed ' + named)

        where:
        count || named
        5 || 'new-1.txt, new-2.txt, new-3.txt, new-4.txt, new-5.txt'
        7 || 'new-1.txt, new-2.txt, new-3.txt, new-4.txt, new-5.txt and 2 more'
    }

    // NFR-S4: the decision role is held to the same check
    def "NFR-S4: a decision run that modified a file is refused too"() {
        when:
        def result = start('modify', request(Role.DECISION, new Policy.WorkspaceWrite()))

        then:
        result.reason().contains('a decision run must leave the working copy unchanged (policy workspaceWrite)')
        result.reason().contains('tracked.txt')
    }

    // NFR-S4: a run that left the working copy as it found it passes through unchanged
    def "NFR-S4: a verdict run that changed nothing keeps its result"() {
        when:
        def result = start('none', request(Role.VERDICT, new Policy.WriteExactly('report.md')))

        then:
        result.status() == Status.COMPLETED
    }

    // FR11: a transform run may write; the check is the read-only roles' only
    def "FR11: a transform run that modified a file is not checked"() {
        when:
        def result = start('modify', request(Role.TRANSFORM, new Policy.WorkspaceWrite()))

        then:
        result.status() == Status.COMPLETED
    }

    // NFR-S4: a check that cannot be made after the run refuses the answer, never passes it on
    def "NFR-S4: a verdict run after which git has no view is refused as unverifiable"() {
        when:
        def result = start('ungit', request(Role.VERDICT))

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('(policy readOnly), and it could not be verified after the run: git status exited ')
        result.usage().provenance() == UsageProvenance.REPORTED
    }

    // NFR-S4: a working copy git cannot read is refused before the executor runs at all
    def "NFR-S4: a verdict run in a working copy git cannot read is refused before it starts"() {
        given:
        repo.resolve('.git').toFile().deleteDir()

        when:
        def result = start('modify', request(Role.VERDICT))

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('(policy readOnly), and its state could not be taken before the run: git status exited ')
        repo.resolve('tracked.txt').toFile().text == 'tracked\n'
    }
}
