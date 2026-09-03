package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.DivergedBranchException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, FR17 of add-sandbox-core, "Resume from the recorded branch" of
 * git-task-persistence, and FR8 of harden-task-branch-contract: {@link
 * ContainerResumeBranch} reconciles the local task branch on refs alone — a
 * remote-tracking-only branch becomes a local ref, a branch behind origin is
 * fast-forwarded, ahead is kept, diverged discards the local line and
 * continues from the origin tip, and a branch that exists nowhere reports
 * false. Runs on real local repositories — no git mocking, no checkout of the
 * task branch.
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

    /** The take path: a tenure is held on the task, which is what authorizes the discard. */
    private ContainerResumeBranch resume() {
        new ContainerResumeBranch(runner, { String taskId ->
            Optional.of(new ClaimEpoch(3L))
        } as ClaimEpochSource)
    }

    /** The manual container resume path: no tracker, no claim, so no tenure on anything. */
    private ContainerResumeBranch claimlessResume() {
        new ContainerResumeBranch(runner, ClaimEpochSource.NONE)
    }

    def "FR6: a branch that exists nowhere reports false, not a phantom resume"() {
        expect: 'no local branch, no remote-tracking ref, nothing to narrow-fetch'
        !resume().ensureLocalBranch(clone, TASK)
    }

    def "FR6: a remote-tracking-only branch becomes a local ref at the tracking tip"() {
        given: 'the branch lives on origin only'
        setOriginBranch(base)
        def logs = LogCaptureSupport.attach(ContainerResumeBranch)

        when:
        def located = resume().ensureLocalBranch(clone, TASK)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        located
        localTip() == base

        and: 'FR5 of harden-logging-observability: adopting another instance\'s work is an anchor'
        def anchors = events.findAll { it.level == Level.INFO }
        anchors.size() == 1
        anchors[0].formattedMessage.contains(TASK)
        anchors[0].formattedMessage.contains('adopting work pushed by another instance')
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

    // FR8 of harden-task-branch-contract: container mode runs the same replica-pair policy host
    // mode does, so divergence resolves under the claim instead of stopping the run. The rule it
    // replaces — throw and let a human reconcile — left a claimed boxed task frozen.
    def "FR8: diverged local and origin tips discard the local line and continue from origin"() {
        given: 'local and origin each carry their own child of the base'
        def localSide = plumbCommit(base, 'local line')
        def originSide = plumbCommit(base, 'origin line')
        setLocalBranch(localSide)
        setOriginBranch(originSide)

        when:
        def located = resume().ensureLocalBranch(clone, TASK)

        then: 'the run continues, on what origin holds'
        noExceptionThrown()
        located
        localTip() == originSide
    }

    // FR8: the discard's justification is the claim protocol, so the claimless container resume
    // (gnomish run --resume, which carries no tracker) stops and reports instead.
    def "FR8: diverged tips with no tenure on the task stop the resume and keep the local line"() {
        given:
        def localSide = plumbCommit(base, 'local line')
        def originSide = plumbCommit(base, 'origin line')
        setLocalBranch(localSide)
        setOriginBranch(originSide)

        when:
        claimlessResume().ensureLocalBranch(clone, TASK)

        then:
        thrown(DivergedBranchException)
        localTip() == localSide
    }
}
