package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.subprocess.Termination
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, FR4 of fix-lifecycle-push (design D2): the one remote-refs read of the factory — what
 * {@code origin} holds for a task branch — plus the local ancestry answer derived from it. Every
 * failure mode degrades without throwing: unreachable remote and branch absent on origin read as
 * "not carried", while a remote tip the clone cannot resolve confirms as UNKNOWN, never absence.
 */
class RemoteBranchTipSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()
    private final RemoteBranchTip remoteTip = new RemoteBranchTip(runner)
    private static final String BRANCH = 'gnomish/task-1'

    private Path clone
    private Path origin

    def setup() {
        clone = initWorkingRepo(tempDir, 'clone')
        Files.writeString(clone.resolve('a.txt'), 'base')
        commitAll(clone, 'base')
        origin = initBareRepo(tempDir, 'origin.git')
        addRemote(clone, 'origin', origin.toString())
        gitOutput(clone, 'checkout', '-b', BRANCH)
    }

    private String commitChange(String content) {
        Files.writeString(clone.resolve('a.txt'), content)
        commitAll(clone, 'round')
        gitOutput(clone, 'rev-parse', 'HEAD').trim()
    }

    private void pushBranch() {
        assert new RefspecPush(runner).push(clone, BRANCH).exitCode() == 0
    }

    def "reads the sha origin holds for the branch"() {
        given:
        def pushed = commitChange('one')
        pushBranch()

        expect:
        remoteTip.read(clone, BRANCH) == Optional.of(pushed)
    }

    def "a branch origin does not carry reads as empty"() {
        given:
        commitChange('one')

        expect:
        remoteTip.read(clone, BRANCH) == Optional.empty()
    }

    def "an unreachable origin reads as empty rather than throwing"() {
        given:
        def unreachable = initWorkingRepo(tempDir, 'unreachable')
        gitOutput(unreachable, 'remote', 'add', 'origin', tempDir.resolve('nowhere.git').toString())

        expect:
        remoteTip.read(unreachable, BRANCH) == Optional.empty()
    }

    def "a commit at the delivered tip, and its ancestors, are carried by origin"() {
        given:
        def first = commitChange('one')
        def second = commitChange('two')
        pushBranch()

        expect:
        remoteTip.carries(clone, BRANCH, second)
        remoteTip.carries(clone, BRANCH, first)
    }

    def "a commit made after the last delivery is not carried"() {
        given:
        commitChange('one')
        pushBranch()
        def undelivered = commitChange('two')

        expect:
        !remoteTip.carries(clone, BRANCH, undelivered)
    }

    def "nothing is carried by a branch origin does not have"() {
        given:
        def local = commitChange('one')

        expect:
        !remoteTip.carries(clone, BRANCH, local)
    }

    // FR7 of bound-subprocess-commands: the three-way answer a caller needs when its push did not
    // run to its own exit — only a remote that actually answered can put a commit's absence on the
    // record, so an unreachable one is UNKNOWN and never ABSENT.
    def "FR7: a commit origin holds is confirmed as carried"() {
        given:
        def delivered = commitChange('one')
        pushBranch()

        expect:
        remoteTip.confirm(clone, BRANCH, delivered) == RemoteBranchTip.Carriage.CARRIES
    }

    def "FR7: an answered read that does not hold the commit is a positive absence"() {
        given:
        commitChange('one')
        pushBranch()
        def undelivered = commitChange('two')

        expect:
        remoteTip.confirm(clone, BRANCH, undelivered) == RemoteBranchTip.Carriage.ABSENT

        and: 'a branch origin does not carry at all is still an answer, so still absence'
        remoteTip.confirm(clone, 'gnomish/never-pushed', undelivered) == RemoteBranchTip.Carriage.ABSENT
    }

    def "FR7: a remote that never answered is unknown, not absent"() {
        given:
        def unreachable = initWorkingRepo(tempDir, 'unreachable-confirm')
        gitOutput(unreachable, 'remote', 'add', 'origin', tempDir.resolve('nowhere.git').toString())

        expect:
        remoteTip.confirm(unreachable, BRANCH, '3333333333333333333333333333333333333333') ==
                RemoteBranchTip.Carriage.UNKNOWN
    }

    def "FR7: a remote tip the clone cannot resolve is unknown, not absent"() {
        given: 'our commit is delivered, then another instance pushes a descendant on top'
        def delivered = commitChange('one')
        pushBranch()
        pushDescendantFromAnotherClone()

        expect: 'the tip is not in the local object database, so nothing can claim absence'
        remoteTip.confirm(clone, BRANCH, delivered) == RemoteBranchTip.Carriage.UNKNOWN
    }

    private void pushDescendantFromAnotherClone() {
        def other = initWorkingRepo(tempDir, 'other')
        addRemote(other, 'origin', origin.toString())
        gitOutput(other, 'fetch', 'origin', BRANCH)
        gitOutput(other, 'checkout', '-b', BRANCH, 'FETCH_HEAD')
        Files.writeString(other.resolve('a.txt'), 'descendant')
        commitAll(other, 'descendant')
        assert new RefspecPush(runner).push(other, BRANCH).exitCode() == 0
    }

    def "FR7: an ancestry command that never ran to its own exit answers unknown"() {
        expect:
        RemoteBranchTip.ancestryVerdict(new GitCommandResult(exit, '', '', termination)) == expected

        where:
        termination | exit || expected
        Termination.EXITED | 0 || RemoteBranchTip.Carriage.CARRIES
        Termination.EXITED | 1 || RemoteBranchTip.Carriage.ABSENT
        Termination.EXITED | 128 || RemoteBranchTip.Carriage.UNKNOWN
        Termination.TIMED_OUT | 0 || RemoteBranchTip.Carriage.UNKNOWN
        Termination.INTERRUPTED | 1 || RemoteBranchTip.Carriage.UNKNOWN
    }

    def "local ancestry answers from the clone's own object database"() {
        given:
        def first = commitChange('one')
        def second = commitChange('two')

        expect:
        remoteTip.isAncestor(clone, first, second)
        remoteTip.isAncestor(clone, second, second)
        !remoteTip.isAncestor(clone, second, first)
    }
}
