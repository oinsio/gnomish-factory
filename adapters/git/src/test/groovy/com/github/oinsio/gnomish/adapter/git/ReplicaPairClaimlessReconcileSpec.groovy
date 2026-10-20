package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8 of harden-task-branch-contract (design D8): the claim gates exactly one move of the replica
 * pair — the discard of a truly diverged local line. Equal, ahead and behind destroy nothing a claim
 * could arbitrate, so a claimless path ({@code gnomish run --resume}) reconciles them exactly as a
 * take under a live claim does. Each posture is built twice, once per tenure, in both media (a
 * worktree and a bare ref), and the two outcomes, local tips and origin tips are compared.
 *
 * <p>The claimed half of each posture, and the claimless refusal of the discard, are pinned by
 * {@link ReplicaPairReconcilerSpec}; this spec owns the comparison.
 */
class ReplicaPairClaimlessReconcileSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def branchName = 'gnomish/PROJ-1'

    def underTenure = { String taskId ->
        Optional.of(new ClaimEpoch(7L))
    } as ClaimEpochSource

    // FR8: the pair reconciles by its posture alone when nothing would be discarded
    // Scenario: lifecycle/task-branch-contract / Equal, ahead and behind reconcile without a claim
    def "FR8: a #posture pair in a #medium reconciles without a claim exactly as under a live claim"() {
        given:
        def claimed = world('claimed', posture, medium)
        def claimless = world('claimless', posture, medium)

        when:
        def claimedOutcome = reconciler(claimed, medium, underTenure).reconcile('PROJ-1', branchName)
        def claimlessOutcome = reconciler(claimless, medium, ClaimEpochSource.NONE).reconcile('PROJ-1', branchName)

        then: 'the same outcome, which is the posture itself'
        claimlessOutcome == expected
        claimedOutcome == expected

        and: 'the local line lands on the same commit under both, by content: kept, or fast-forwarded'
        subjectAt(claimless.local) == subjectAt(claimed.local)
        subjectAt(claimless.local) == expectedSubject

        and: 'origin is untouched by either'
        subjectAt(claimless.bare, "refs/heads/${branchName}") == subjectAt(claimed.bare, "refs/heads/${branchName}")

        where:
        posture | medium || expected | expectedSubject
        'equal' | 'worktree' || DivergenceOutcome.EQUAL | 'a.txt'
        'ahead' | 'worktree' || DivergenceOutcome.AHEAD | 'c.txt'
        'behind' | 'worktree' || DivergenceOutcome.BEHIND | 'b.txt'
        'equal' | 'clone' || DivergenceOutcome.EQUAL | 'a.txt'
        'ahead' | 'clone' || DivergenceOutcome.AHEAD | 'c.txt'
        'behind' | 'clone' || DivergenceOutcome.BEHIND | 'b.txt'
    }

    private ReplicaPairReconciler reconciler(Map<String, Path> world, String medium, ClaimEpochSource epochs) {
        medium == 'worktree'
                ? ReplicaPairReconciler.forWorktree(runner, world.worktree, epochs)
                : ReplicaPairReconciler.forClone(runner, world.clone, epochs)
    }

    /**
     * A bare origin, a clone holding the task branch, and — for the worktree medium — the branch
     * checked out as a worktree; then the posture: nothing, a local commit origin lacks, or an
     * origin commit the local line lacks.
     */
    private Map<String, Path> world(String name, String posture, String medium) {
        def root = Files.createDirectories(tempDir.resolve(name))
        def bare = initBareRepo(root, 'origin.git')
        def seed = initWorkingRepo(root, 'seed')
        commit(seed, 'seed.txt', 'seed')
        FixtureGitConfig.addRemote(seed, 'origin', bare.toString())
        runner.run(seed, 'push', 'origin', 'HEAD:refs/heads/main')
        runner.run(seed, 'checkout', '-b', branchName)
        commit(seed, 'a.txt', 'first')
        runner.run(seed, 'push', 'origin', branchName)

        def clone = seedClone(root, bare.toString(), root.resolve('clone'))
        fetchFromOrigin(clone, "refs/heads/${branchName}:refs/remotes/origin/${branchName}")
        runner.run(clone, 'branch', branchName, "refs/remotes/origin/${branchName}")
        def worktree = root.resolve('worktree')
        if (medium == 'worktree') {
            def add = runner.run(clone, 'worktree', 'add', worktree.toString(), branchName)
            assert add.exitCode() == 0: "worktree add failed: ${add.stderr()}"
        }
        Path local = medium == 'worktree' ? worktree : clone

        if (posture == 'ahead') {
            if (medium == 'worktree') {
                commit(worktree, 'c.txt', 'unpushed')
            } else {
                commitOnBranch(clone, 'c.txt')
            }
        } else if (posture == 'behind') {
            commit(seed, 'b.txt', 'second')
            runner.run(seed, 'push', 'origin', branchName)
        }
        [bare: bare, clone: clone, worktree: worktree, local: local]
    }

    /** A commit on the clone's task branch with no working tree, as a boxed task's harvest leaves one. */
    private void commitOnBranch(Path clone, String subject) {
        String parent = gitOutput(clone, 'rev-parse', "refs/heads/${branchName}")
        String tree = gitOutput(clone, 'rev-parse', "${parent}^{tree}")
        String child = gitOutput(clone, 'commit-tree', tree, '-p', parent, '-m', subject)
        gitOutput(clone, 'update-ref', "refs/heads/${branchName}", child)
    }

    private String subjectAt(Path repo, String ref = null) {
        String revision = ref ?: (repo.fileName.toString() == 'clone' ? "refs/heads/${branchName}" : 'HEAD')
        gitOutput(repo, 'log', '-1', '--format=%s', revision)
    }
}
