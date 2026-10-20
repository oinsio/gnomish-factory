package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gittransfer.GitInvocation
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, NFR-S1 of fix-lifecycle-push (design D2): the one push command every push point in the
 * factory runs — exact refspec {@code origin branch:branch}, never a force flag — handing its raw
 * result back rather than deciding anything about it.
 */
class RefspecPushSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()
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

    private String localTip() {
        gitOutput(clone, 'rev-parse', 'HEAD').trim()
    }

    def "pushes the branch to the identically named ref on origin and reports success"() {
        when:
        def result = new RefspecPush(runner).push(clone, BRANCH)

        then:
        result.exitCode() == 0
        gitOutput(origin, 'rev-parse', BRANCH).trim() == localTip()
    }

    // Design D2: the refspec is EXPLICIT — `origin <branch>:<branch>`, never a bare branch name
    // leaning on git's implicit refspec inference (which `push.default` can redirect) and never a
    // forced `+`-prefixed one. Asserted over the argv itself through the runner's port (design D27
    // of supervise-daemon-loops-and-embed-dashboard): pushing successfully proves the command works,
    // not that it is spelled this way. The runner adds its own stall-detection options in front, a
    // push being a network command (FR4 of bound-subprocess-commands), pinned by its own specs; the
    // argv handed to it is this class's.
    // Scenario: git/git-task-persistence / Push names exactly the task branch
    def "the command is the exact explicit refspec, with no other argument"() {
        given: 'a git whose push succeeds'
        def git = new ScriptedGit().answer(['push'], 0)

        when:
        new RefspecPush(git).push(clone, BRANCH)

        then: 'one invocation, exactly the explicit refspec of the branch alone — the mid-round push carries no register (FR13 of tighten-spec-contract)'
        git.calls()*.argv == [
            GitInvocation.configPairs() + [
                'push',
                '--no-follow-tags',
                '--no-recurse-submodules',
                '--end-of-options',
                'origin',
                "refs/heads/${BRANCH}:refs/heads/${BRANCH}".toString()
            ]
        ]
    }

    // The production shape (FR1): a host-mode lifecycle push runs from the CLONE, whose HEAD is on
    // the project's default branch — the task branch is checked out in a linked worktree, or in no
    // worktree at all. The push must therefore deliver a branch that is not HEAD, which only the
    // explicit refspec does.
    def "pushes a task branch the pushing repo does not have checked out"() {
        given:
        def branchTip = localTip()
        gitOutput(clone, 'checkout', '-q', '-')

        when:
        def result = new RefspecPush(runner).push(clone, BRANCH)

        then:
        result.exitCode() == 0
        gitOutput(origin, 'rev-parse', BRANCH).trim() == branchTip
    }

    def "a rejected push is reported, not forced through"() {
        given: 'origin already carries a commit the local branch does not'
        def otherClone = tempDir.resolve('other-clone')
        seedClone(tempDir, origin.toString(), otherClone, '-q')
        gitOutput(otherClone, 'checkout', '-q', '-b', BRANCH)
        Files.writeString(otherClone.resolve('divergent.txt'), 'someone else')
        commitAll(otherClone, 'divergent')
        gitOutput(otherClone, 'push', 'origin', "${BRANCH}:${BRANCH}")
        def remoteTipBefore = gitOutput(origin, 'rev-parse', BRANCH).trim()

        when:
        def result = new RefspecPush(runner).push(clone, BRANCH)

        then:
        result.exitCode() != 0
        gitOutput(origin, 'rev-parse', BRANCH).trim() == remoteTipBefore
    }
}
