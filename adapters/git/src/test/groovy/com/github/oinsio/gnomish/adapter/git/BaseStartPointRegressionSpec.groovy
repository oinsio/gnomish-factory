package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR15, NFR-S1 of add-base-ref-resolution (D12, revised 2026-09-10): the three postures a real
 * operator clone actually presents, each of which sent a task branch to the wrong commit before
 * the port took a peeled commit — reproduced here against a real bare {@code origin}.
 *
 * <ul>
 *   <li>a local branch of the base's name, behind origin;
 *   <li>a base that exists only on origin, with no local branch at all;
 *   <li>a local <em>tag</em> carrying the base's name, pointing somewhere else — git's own
 *       bare-name preference, and the one an attacker with push access to tags could arrange.
 * </ul>
 *
 * <p>The assertion is one identity per posture (`.claude/rules/testing.md`, "Invariant specs across
 * a flow"): the commit the refresh read back, the task branch's first parent, and {@code
 * task.json}'s {@code baseCommit} are one SHA. Component specs prove each link; only this proves
 * they are joined.
 */
class BaseStartPointRegressionSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()

    Path origin
    Path clone
    String defaultBranch

    def setup() {
        Path seed = initWorkingRepo(tempDir, 'seed')
        Files.writeString(seed.resolve('a.txt'), 'first')
        commitAll(seed, 'init')
        origin = addConvergedOrigin(seed, tempDir)
        defaultBranch = currentBranch(seed)

        clone = tempDir.resolve('clone')
        seedClone(tempDir, origin.toString(), clone)

        // Origin moves on AFTER the clone was taken: an empty commit, so origin's tree is identical
        // and only the commit id distinguishes "origin's tip" from "what this clone last saw".
        assert runner.run(seed, '-c', 'user.email=a@b.c', '-c', 'user.name=a',
        'commit', '--allow-empty', '-m', 'origin moves ahead').exitCode() == 0
        assert runner.run(seed, 'push', 'origin', "HEAD:refs/heads/${defaultBranch}").exitCode() == 0
    }

    private BaseRefresh refresh() {
        // Virtual time: the retry's production bound is never elapsed here, only its policy used
        // (`.claude/rules/testing.md`, "Time is injected in tests").
        new BaseRefresh(runner, new GitInfrastructureRetry(
                        { Duration ignored -> } as Sleeper, GitInfrastructureRetry.DEFAULT_ATTEMPTS, Duration.ofMillis(1)))
    }

    private String createTaskFrom(String baseName, String taskId) {
        def refreshed = refresh().refresh(clone, baseName)
        assert refreshed instanceof BaseRefreshOutcome.Refreshed:
        "refresh of '${baseName}' did not succeed: ${refreshed}"
        String commit = (refreshed as BaseRefreshOutcome.Refreshed).commit()
        new GitTaskRepository(runner, clone, tempDir.resolve('worktrees'), ClaimEpochSource.NONE).createTask(
                new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                ObjectId.of(commit),
                new BasePin(baseName, (refreshed as BaseRefreshOutcome.Refreshed).kind(), BaseRule.CONFIGURED_DEFAULT),
                TaskState.atStageStart('build'))
        commit
    }

    private String taskJsonBaseCommit(String taskId) {
        def json = gitOutput(clone, 'show', "gnomish/${taskId}:.gnomish-task/task.json")
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(UntrustedText.branchDocument(json))).baseCommit()
    }

    // FR15, NFR-S1: the clone's own refs/heads/<default> is a commit behind origin. Before the peel,
    //     `rev-parse <default>` answered with the stale local branch while the refresh had just
    //     fetched origin's tip — law and branch naming two commits.
    def "FR15: a local branch behind origin does not become the start point"() {
        when:
        def refreshed = createTaskFrom(defaultBranch, 'PROJ-1')

        then: 'the clone really was behind — otherwise this scenario proves nothing'
        gitOutput(clone, 'rev-parse', "refs/heads/${defaultBranch}") != refreshed

        and: 'the refreshed commit, the branch first parent and the recorded baseCommit are one SHA'
        gitOutput(clone, 'rev-parse', 'gnomish/PROJ-1~1') == refreshed
        taskJsonBaseCommit('PROJ-1') == refreshed
        gitOutput(clone, 'rev-parse', "refs/remotes/origin/${defaultBranch}") == refreshed
    }

    // FR15: a release branch that exists only on origin. Before the peel this failed createTask
    //     outright after a successful fetch — the fetch lands in refs/remotes/origin/<n>, which a
    //     bare name never reaches.
    def "FR15: a base existing only on origin resolves and becomes the start point"() {
        given: 'origin gains a branch this clone has never had'
        Path seed = tempDir.resolve('seed')
        assert runner.run(seed, 'push', 'origin', 'HEAD:refs/heads/release/1.18').exitCode() == 0
        def originTip = gitOutput(origin, 'rev-parse', 'refs/heads/release/1.18')

        when:
        def refreshed = createTaskFrom('release/1.18', 'PROJ-2')

        then:
        refreshed == originTip
        gitOutput(clone, 'rev-parse', 'gnomish/PROJ-2~1') == refreshed
        taskJsonBaseCommit('PROJ-2') == refreshed

        and: 'no local branch of that name was created — the clone is untouched'
        gitExitCode(clone, 'rev-parse', '--verify', '--quiet', 'refs/heads/release/1.18') != 0
    }

    // FR15, NFR-S1: a local tag carrying the base's name. Git resolves an unqualified name to the
    //     tag before the branch, so this posture silently redirected the task branch onto whatever
    //     commit the tag named — the security half of the defect.
    def "FR15: a planted local tag carrying the base's name cannot redirect the start point"() {
        given: 'a local tag named exactly like the base, pointing at the clone stale commit'
        def stale = gitOutput(clone, 'rev-parse', 'HEAD')
        assert runner.run(clone, 'tag', defaultBranch, stale).exitCode() == 0

        when:
        def refreshed = createTaskFrom(defaultBranch, 'PROJ-3')

        then: 'the tag really would have won a bare-name resolution'
        gitOutput(clone, 'rev-parse', defaultBranch) == stale
        stale != refreshed

        and: 'and it did not'
        gitOutput(clone, 'rev-parse', 'gnomish/PROJ-3~1') == refreshed
        taskJsonBaseCommit('PROJ-3') == refreshed
    }

    // FR15, NFR-S1: nothing the operator owns moves. The refresh may write refs/remotes/origin/* and
    //     the creation writes its own task branch; every other ref, every tag and HEAD stay put.
    def "NFR-S1: creating the task leaves the clone's own branches, tags and HEAD unchanged"() {
        given:
        assert runner.run(clone, 'tag', 'v9', 'HEAD').exitCode() == 0
        def headBefore = gitOutput(clone, 'rev-parse', 'HEAD')
        def branchesBefore = gitOutput(clone, 'for-each-ref', '--format=%(refname) %(objectname)', 'refs/heads/')
        def tagsBefore = gitOutput(clone, 'for-each-ref', '--format=%(refname) %(objectname)', 'refs/tags/')

        when:
        createTaskFrom(defaultBranch, 'PROJ-4')

        then:
        gitOutput(clone, 'rev-parse', 'HEAD') == headBefore
        gitOutput(clone, 'for-each-ref', '--format=%(refname) %(objectname)', 'refs/tags/') == tagsBefore

        and: 'the only new local branch is the task branch itself'
        def branchesAfter = gitOutput(clone, 'for-each-ref', '--format=%(refname) %(objectname)', 'refs/heads/')
        (branchesAfter.readLines() - branchesBefore.readLines())*.split(' ')*.first() == ['refs/heads/gnomish/PROJ-4']
    }

    // FR15, NFR-R1: a second pass over an already-created branch refuses, exactly as before this
    //     revision — no durable step was added or reordered, so recovery behavior is untouched.
    def "NFR-R1: a second creation pass refuses and leaves the branch where it was"() {
        given:
        def refreshed = createTaskFrom(defaultBranch, 'PROJ-5')
        def tipAfterFirst = gitOutput(clone, 'rev-parse', 'gnomish/PROJ-5')

        when:
        createTaskFrom(defaultBranch, 'PROJ-5')

        then:
        thrown(GitTaskRepositoryException)
        gitOutput(clone, 'rev-parse', 'gnomish/PROJ-5') == tipAfterFirst
        gitOutput(clone, 'rev-parse', 'gnomish/PROJ-5~1') == refreshed
    }
}
