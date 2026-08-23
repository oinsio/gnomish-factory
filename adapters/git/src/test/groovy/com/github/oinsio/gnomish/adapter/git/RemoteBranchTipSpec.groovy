package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, FR4 of fix-lifecycle-push (design D2): the one remote-refs read of the factory — what
 * {@code origin} holds for a task branch — plus the local ancestry answer derived from it. Every
 * failure mode (unreachable remote, branch absent on origin, tip unknown locally) degrades to
 * "not carried" rather than throwing.
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
