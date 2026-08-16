package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.DivergedBranchException
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, FR17 of add-sandbox-core, "Resume from the recorded branch" of
 * git-task-persistence: {@link ContainerResumeBranch} reconciles the local task
 * branch on refs alone — a remote-tracking-only branch becomes a local ref, a
 * branch behind origin is fast-forwarded, ahead is kept, diverged throws with
 * both tips named, and a branch that exists nowhere reports false. Runs on real
 * local repositories — no git mocking, no checkout of the task branch.
 */
class ContainerResumeBranchSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'RES-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path clone
    Path origin
    String base

    def setup() {
        clone = initWorkingRepo(tempDir, 'clone')
        new File(clone.toFile(), 'a.txt').text = 'seed'
        commitAll(clone)
        base = gitOutput(clone, 'rev-parse', 'HEAD')
        origin = initBareRepo(tempDir, 'origin.git')
        addRemote(clone, 'origin', origin.toString())
    }

    /** A plumbing child of {@code parent} on the (never checked-out) task branch history. */
    private String plumbCommit(String parent, String message) {
        def tree = gitOutput(clone, 'rev-parse', 'HEAD^{tree}')
        gitOutput(clone, '-c', 'user.email=g@b.c', '-c', 'user.name=g',
                'commit-tree', tree, '-p', parent, '-m', message)
    }

    private void setLocalBranch(String sha) {
        gitOutput(clone, 'update-ref', 'refs/heads/' + BRANCH, sha)
    }

    private void setOriginBranch(String sha) {
        gitOutput(clone, 'push', 'origin', sha + ':refs/heads/' + BRANCH)
        gitOutput(clone, 'fetch', 'origin', BRANCH + ':refs/remotes/origin/' + BRANCH)
    }

    private String localTip() {
        gitOutput(clone, 'rev-parse', 'refs/heads/' + BRANCH)
    }

    private ContainerResumeBranch resume() {
        new ContainerResumeBranch(runner)
    }

    def "FR6: a branch that exists nowhere reports false, not a phantom resume"() {
        expect: 'no local branch, no remote-tracking ref, nothing to narrow-fetch'
        !resume().ensureLocalBranch(clone, TASK)
    }

    def "FR6: a remote-tracking-only branch becomes a local ref at the tracking tip"() {
        given: 'the branch lives on origin only'
        setOriginBranch(base)

        expect:
        resume().ensureLocalBranch(clone, TASK)
        localTip() == base
    }

    def "FR6: a local branch behind origin is fast-forwarded to the origin tip"() {
        given: 'local sits at the base while origin advanced one commit'
        setLocalBranch(base)
        def ahead = plumbCommit(base, 'origin work')
        setOriginBranch(ahead)

        expect: 'resume keeps the branch and its ref now carries the origin tip'
        resume().ensureLocalBranch(clone, TASK)
        localTip() == ahead
    }

    def "FR6: a local branch ahead of origin keeps its own tip untouched"() {
        given: 'origin sits at the base while local advanced one commit'
        setOriginBranch(base)
        def ahead = plumbCommit(base, 'local work')
        setLocalBranch(ahead)

        expect: 'ahead is kept — no fast-forward, no exception'
        resume().ensureLocalBranch(clone, TASK)
        localTip() == ahead
    }

    def "FR6: diverged local and origin tips throw, naming both tips for the operator"() {
        given: 'local and origin each carry their own child of the base'
        def localSide = plumbCommit(base, 'local line')
        def originSide = plumbCommit(base, 'origin line')
        setLocalBranch(localSide)
        setOriginBranch(originSide)

        when:
        resume().ensureLocalBranch(clone, TASK)

        then: 'never a force update — the operator gets both real tips to reconcile'
        def ex = thrown(DivergedBranchException)
        ex.message.contains(localSide)
        ex.message.contains(originSide)

        and: 'the local tip is untouched'
        localTip() == localSide
    }
}
