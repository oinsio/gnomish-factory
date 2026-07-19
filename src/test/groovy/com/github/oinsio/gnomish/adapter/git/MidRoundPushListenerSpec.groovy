package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.agent.AgentProgressEvent
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11 of add-git-workflow ("the adapter also notices gnome commits mid-round (tip moved) and
 * pushes best-effort") and the "Gnome commit triggers a push" scenario of the
 * git-task-persistence delta spec: {@link MidRoundPushListener} is the agent-cli live loop's
 * seam for this — an {@link com.github.oinsio.gnomish.adapter.agent.AgentProgressListener} that
 * notices {@code HEAD} moving between two delivered progress events and delegates to {@link
 * BestEffortPush} using the exact same round-boundary preconditions as the post-round push
 * (task 3.8), never reimplementing them.
 */
class MidRoundPushListenerSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path repo
    Path bareRepo

    def toolEvent = new AgentProgressEvent.ToolStarted('Bash')

    def setup() {
        repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'first'
        runner.run(repo, 'add', 'a.txt')
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        runner.run(repo, 'checkout', '-q', '-b', 'gnomish/PROJ-1')

        bareRepo = initBareRepo(tempDir, 'origin.git')
        runner.run(repo, 'remote', 'add', 'origin', bareRepo.toString())
    }

    private String currentHead() {
        runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
    }

    private void gnomeCommit(String fileName = 'gnome.txt', String content = 'gnome work') {
        new File(repo.toFile(), fileName).text = content
        runner.run(repo, 'add', fileName)
        runner.run(repo, '-c', 'user.email=g@b.c', '-c', 'user.name=g', 'commit', '-m', 'gnome commit')
    }

    def "FR11: no push when HEAD has not moved since construction"() {
        given:
        def listener = new MidRoundPushListener(runner, repo, 'PROJ-1', 'implement', 0, 'gnomish/PROJ-1')

        when:
        listener.onProgress(toolEvent)

        then:
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').exitCode() != 0
    }

    def "FR11: a gnome commit mid-round triggers a best-effort push on the next event"() {
        given:
        def listener = new MidRoundPushListener(runner, repo, 'PROJ-1', 'implement', 0, 'gnomish/PROJ-1')
        gnomeCommit()

        when:
        listener.onProgress(toolEvent)

        then:
        def remoteHead = runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().trim()
        remoteHead == currentHead()
    }

    def "FR11: a second event with an unchanged tip does not push again"() {
        given:
        def listener = new MidRoundPushListener(runner, repo, 'PROJ-1', 'implement', 0, 'gnomish/PROJ-1')
        gnomeCommit()
        listener.onProgress(toolEvent)
        def firstPushedHead = runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().trim()

        when: 'the remote branch is force-updated out of band, so a repeated push would be observable'
        runner.run(repo, 'checkout', '-q', '-b', 'scratch')
        runner.run(repo, 'checkout', '-q', 'gnomish/PROJ-1')
        listener.onProgress(toolEvent)

        then: 'no additional push attempt happened - the remote tip is unchanged'
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().trim() == firstPushedHead
    }

    def "NFR-S1: push is skipped when HEAD moved but is off the expected branch"() {
        given:
        def listener = new MidRoundPushListener(runner, repo, 'PROJ-1', 'implement', 0, 'gnomish/PROJ-1')
        runner.run(repo, 'checkout', '-q', '-b', 'not-the-task-branch')
        gnomeCommit()

        when:
        listener.onProgress(toolEvent)

        then:
        noExceptionThrown()
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').exitCode() != 0
    }

    def "NFR-S1: push is skipped when the observed baseline is no longer an ancestor of HEAD"() {
        given: 'an orphan commit replaces branch history after construction, stranding the baseline tip'
        def listener = new MidRoundPushListener(runner, repo, 'PROJ-1', 'implement', 0, 'gnomish/PROJ-1')
        runner.run(repo, 'checkout', '-q', '--orphan', 'rewritten-history')
        new File(repo.toFile(), 'rewritten.txt').text = 'rewritten history'
        runner.run(repo, 'add', 'rewritten.txt')
        runner.run(repo, '-c', 'user.email=g@b.c', '-c', 'user.name=g', 'commit', '-m', 'orphan root')
        runner.run(repo, 'branch', '-f', 'gnomish/PROJ-1', 'rewritten-history')
        runner.run(repo, 'checkout', '-q', 'gnomish/PROJ-1')

        when:
        listener.onProgress(toolEvent)

        then:
        noExceptionThrown()
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').exitCode() != 0
    }

    def "FR11: no remote configured means onProgress never throws even after a tip movement"() {
        given:
        runner.run(repo, 'remote', 'remove', 'origin')
        def listener = new MidRoundPushListener(runner, repo, 'PROJ-1', 'implement', 0, 'gnomish/PROJ-1')
        gnomeCommit()

        when:
        listener.onProgress(toolEvent)

        then:
        noExceptionThrown()
    }
}
