package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.BranchStateResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, FR9, M4, NFR-S1 of own-git-invocation-policy (design D3, D8) — the identity claim of the
 * invocation policy on real git: the factory's own git paths, driven through the production adapters
 * ({@link InvocationTaskFlow}) on a clone carrying the shared fixture's adversarial local configuration
 * ({@link AdversarialLocalConfig}) or a hook defined in the configuration, run none of the planted
 * programs, read through a planted replace ref, and leave the clone's
 * {@code .git/config} byte-identical. Component specs prove each launcher carries the pairs; only
 * this spec proves that the pairs, together, defeat the posture on the paths the factory runs.
 *
 * <p>The spec's own seeding runs through {@link #seed}, which neutralizes the posture by its own
 * {@code -c} pairs, independent of {@code GitInvocation}: with the owner's pairs emptied, the red run
 * points at the production path, not at the fixture.
 */
class GitInvocationIdentitySpec extends Specification implements BareGitRepoFixture {

    static final String TASK = InvocationTaskFlow.TASK
    static final String BRANCH = InvocationTaskFlow.BRANCH
    static final List<String> CONFIGURED_EVENTS = [
        'reference-transaction',
        'post-checkout',
        'post-index-change'
    ]

    @TempDir
    Path tempDir

    final GitProcessRunner runner = new GitProcessRunner()
    Path clone
    Path origin
    RegisteredClone registeredClone
    InvocationTaskFlow flow
    byte[] configBefore

    def setup() {
        clone = initWorkingRepo(tempDir, 'clone')
        new File(clone.toFile(), 'a.txt').text = 'seed'
        seed(clone, 'add', 'a.txt')
        seed(clone, 'commit', '-q', '-m', 'seed')
        origin = initBareRepo(tempDir, 'origin.git')
        addRemote(clone, 'origin', origin.toString())
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), clone)
        flow = new InvocationTaskFlow(runner, registeredClone, tempDir)
        configBefore = Files.readAllBytes(clone.resolve('.git/config'))
    }

    def cleanup() {
        assertNothingPlantedRan(clone)
        assert Files.readAllBytes(clone.resolve('.git/config')) == configBefore
    }

    // Scenario: git/git-invocation-policy / A planted local configuration runs nothing
    def "FR8: the host task flow — branch, worktree add, lifecycle commit, attempt commit, register transaction, bare-object commit — runs nothing planted"() {
        when: 'the lifecycle start: branch creation, worktree add, the STARTED commit and its register transaction'
        flow.startTask()

        and: 'a round persisted in the worktree: commit-tree, then the register moved by update-ref --stdin'
        flow.persistRound('verify')

        and: 'a commit written through the git-object library'
        def written = flow.forge(flow.registeredCommit(), 'notes.txt', 'bare-object write')

        then: 'every operation landed'
        gitOutput(clone, 'rev-parse', BRANCH) == flow.registeredCommit()
        gitOutput(clone, 'log', '-1', '--format=%s', flow.registeredCommit()) == ServiceCommitMessages.round('verify', 1)
        gitOutput(clone, 'rev-parse', 'refs/heads/forged') == written

        and: 'nothing planted ran, and .git/config is byte-identical (cleanup)'
        AdversarialLocalConfig.ran(clone).isEmpty()
    }

    // Scenario: git/git-invocation-policy / A hook defined in the configuration does not run
    def "FR9: a hook defined in the configuration runs on none of branch, worktree add, lifecycle commit, attempt commit or register transaction"() {
        given: 'one hook defined by hook.<name>.command, on three events, which core.hooksPath does not reach'
        Path hook = StandIn.link(tempDir.resolve('configured-hook'), AdversarialLocalConfig.PRESET)
        FixtureGitConfig.set(clone, 'hook.planted.command', hook.toString())
        CONFIGURED_EVENTS.each {
            FixtureGitConfig.add(clone, 'hook.planted.event', it)
        }
        configBefore = Files.readAllBytes(clone.resolve('.git/config'))

        and: 'git knows the hook for every one of its events'
        CONFIGURED_EVENTS.each {
            assert gitOutput(clone, 'hook', 'list', it).contains('planted')
        }

        when:
        flow.startTask()
        flow.persistRound('verify')

        then: 'every operation landed'
        gitOutput(clone, 'rev-parse', BRANCH) == flow.registeredCommit()
        gitOutput(clone, 'log', '-1', '--format=%s', flow.registeredCommit()) == ServiceCommitMessages.round('verify', 1)

        and: 'the configured hook never ran; .git/config is byte-identical apart from the plant (cleanup)'
        !Files.exists(StandIn.log(hook))
    }

    // Scenario: git/git-invocation-policy / Replace refs do not change what the factory reads
    def "FR8, NFR-S1: a state read at the registered commit sees the original through a planted replace ref"() {
        given: 'a task whose registered commit records the verify stage'
        flow.startTask()
        flow.persistRound('verify')
        String registered = flow.registeredCommit()

        and: 'a replace ref substituting a forged commit whose state names another stage'
        String original = gitOutput(clone, 'show', "${registered}:.gnomish-task/state.json")
        assert original.contains('"verify"')
        String forged = flow.forge(registered, '.gnomish-task/state.json', original.replace('"verify"', '"forged"'))
        seed(clone, 'update-ref', "refs/replace/${registered}", forged)

        and: 'the substitution is in force for a git that honours replace refs'
        gitOutput(clone, '-c', 'core.useReplaceRefs=true', 'show', "${registered}:.gnomish-task/state.json")
                .contains('"forged"')

        when:
        def result = new BranchStateReader(runner, VirtualTimeGitRetries.gitInfrastructure()).read(clone, TASK)

        then: 'the factory read the original content'
        (result as BranchStateResult.Found).report().currentStage() == 'verify'
    }

    // Scenario: git/git-invocation-policy / A planted pre-push hook does not run on a factory push
    def "FR8: a push of the task branch to a bare origin runs no planted pre-push hook and lands"() {
        given: 'the task branch, seeded so the push is the only factory path here, and a planted executable pre-push hook'
        seed(clone, 'branch', BRANCH)
        Path prePush = StandIn.link(
                AdversarialLocalConfig.programs(clone)['pre-commit'].resolveSibling('pre-push'), AdversarialLocalConfig.PRESET)

        when:
        def pushed = new RefspecPush(runner).push(clone, BRANCH)

        then:
        pushed.exitCode() == 0
        gitOutput(origin, 'rev-parse', "refs/heads/${BRANCH}") == gitOutput(clone, 'rev-parse', BRANCH)
        !Files.exists(StandIn.log(prePush))
    }

    // Scenario: git/git-invocation-policy / Resuming another instance's branch writes no configuration
    def "FR8: adopting a remote-tracking branch with upstream auto-setup forced on writes no configuration"() {
        given: "another instance's branch on origin, known here only as a remote-tracking ref"
        String tip = gitOutput(clone, 'rev-parse', 'HEAD')
        seed(clone, 'push', '-q', 'origin', "${tip}:refs/heads/${BRANCH}")
        seed(clone, 'update-ref', "refs/remotes/origin/${BRANCH}", tip)
        assert gitOutput(clone, 'config', '--local', '--get', 'branch.autoSetupMerge') == 'always'

        when:
        def adopted = new ContainerResumeBranch(runner, ClaimEpochSource.NONE, VirtualTimeGitRetries.gitInfrastructure())
                .ensureLocalBranch(clone, TASK)

        then: 'the local branch exists at the tracking tip; .git/config is byte-identical (cleanup)'
        adopted == new BranchAdoption.Adopted(TASK)
        gitOutput(clone, 'rev-parse', "refs/heads/${BRANCH}") == tip
    }

    /** The spec's own seeding: git with the posture neutralized by this spec's pairs, not the owner's. */
    private String seed(Path repo, String... args) {
        gitOutput(repo, *[
            '-c',
            'core.hooksPath=/dev/null',
            '-c',
            'commit.gpgSign=false',
            '-c',
            'core.fsmonitor=false',
            '-c',
            'user.email=a@b.c',
            '-c',
            'user.name=a'
        ], *args)
    }
}
