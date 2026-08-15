package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, NFR-R1 of add-git-workflow: a round is persisted as one commit of the
 * entire working tree (gnome changes + state.json + trace.jsonl); persist
 * failure is a strict port — it throws, never swallowed.
 */
class GitAttemptPersistenceSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path repo

    def setup() {
        repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'first'
        runner.run(repo, 'add', 'a.txt')
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        runner.run(repo, 'checkout', '-q', '-b', 'gnomish/PROJ-1')
    }

    private void gnomeCommit(String fileName) {
        new File(repo.toFile(), fileName).text = 'gnome wrote this'
        runner.run(repo, 'add', fileName)
        runner.run(repo, '-c', 'user.email=gnome@b.c', '-c', 'user.name=gnome', 'commit', '-m', 'gnome work')
    }

    private static TaskState sampleState() {
        TaskState.atStageStart('implement')
    }

    private static ToolTrace sampleTrace(String stage, int round) {
        new ToolTrace(new AttemptKey('PROJ-1', stage, round), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(100))
        ])
    }

    def "FR2: persisting a round produces exactly one new commit on top of the previous HEAD"() {
        given:
        def persistence = new GitAttemptPersistence(runner, repo, 'PROJ-1')
        def headBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()

        when:
        persistence.persist('PROJ-1', sampleState(), sampleTrace('implement', 0))

        then:
        def log = runner.run(repo, 'log', '--format=%H', "${headBefore}..HEAD")
        log.stdout().trim().readLines().size() == 1
    }

    def "FR2: the commit contains a round-trippable state.json and the trace file at the correct path"() {
        given:
        def persistence = new GitAttemptPersistence(runner, repo, 'PROJ-1')
        def state = sampleState()
        def trace = sampleTrace('implement', 2)

        when:
        persistence.persist('PROJ-1', state, trace)

        then:
        def stateJson = runner.run(repo, 'show', 'HEAD:.gnomish-task/state.json').stdout()
        def rebuilt = StateJsonMapper.fromDto(StateJsonMapper.readDto(stateJson))
        rebuilt == state

        and:
        def traceFile = runner.run(repo, 'show', 'HEAD:.gnomish-task/attempts/implement/2/trace.jsonl')
        traceFile.exitCode() == 0
        traceFile.stdout().contains('"tool":"bash"')
    }

    def "FR2: the commit message matches ServiceCommitMessages.round(stage, round)"() {
        given:
        def persistence = new GitAttemptPersistence(runner, repo, 'PROJ-1')

        when:
        persistence.persist('PROJ-1', sampleState(), sampleTrace('verify', 3))

        then:
        def message = runner.run(repo, 'log', '-1', '--format=%s').stdout().trim()
        message == ServiceCommitMessages.round('verify', 3)
    }

    def "FR2: pre-existing uncommitted gnome file changes are included in the same round commit"() {
        given:
        def persistence = new GitAttemptPersistence(runner, repo, 'PROJ-1')
        new File(repo.toFile(), 'gnome-change.txt').text = 'written by the gnome'

        when:
        persistence.persist('PROJ-1', sampleState(), sampleTrace('implement', 0))

        then:
        def show = runner.run(repo, 'show', '--stat', 'HEAD').stdout()
        show.contains('gnome-change.txt')
        def status = runner.run(repo, 'status', '--porcelain')
        status.stdout().trim().isEmpty()
    }

    def "NFR-R1: a persist failure throws GitPersistFailedException instead of failing silently"() {
        given: 'a valid task-branch worktree whose git index lock is already held, forcing git add/commit to fail'
        def persistence = new GitAttemptPersistence(runner, repo, 'PROJ-1')
        new File(repo.toFile(), '.git/index.lock').text = 'held by another process'

        when:
        persistence.persist('PROJ-1', sampleState(), sampleTrace('implement', 0))

        then:
        def e = thrown(GitPersistFailedException)
        e.message.contains('PROJ-1')
    }

    def "FR12: gnome commits during the round are preserved and the round-closing commit builds on them"() {
        given:
        def persistence = new GitAttemptPersistence(runner, repo, 'PROJ-1')
        def headBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
        gnomeCommit('gnome-1.txt')
        gnomeCommit('gnome-2.txt')
        gnomeCommit('gnome-3.txt')

        when:
        persistence.persist('PROJ-1', sampleState(), sampleTrace('implement', 0))

        then: 'all three gnome commits plus the round-closing commit reach the branch'
        def log = runner.run(repo, 'log', '--format=%H', "${headBefore}..HEAD")
        log.stdout().trim().readLines().size() == 4
    }

    def "FR12: a history rewrite since the previous round tip aborts persist"() {
        given: 'the adapter remembers HEAD at construction as the previous tip'
        def persistence = new GitAttemptPersistence(runner, repo, 'PROJ-1')

        and: 'the gnome discards that tip entirely: an orphan commit replaces the branch history'
        runner.run(repo, 'checkout', '-q', '--orphan', 'rewritten-history')
        new File(repo.toFile(), 'rewritten.txt').text = 'rewritten history'
        runner.run(repo, 'add', 'rewritten.txt')
        runner.run(repo, '-c', 'user.email=gnome@b.c', '-c', 'user.name=gnome', 'commit', '-m', 'orphan root')
        runner.run(repo, 'branch', '-f', 'gnomish/PROJ-1', 'rewritten-history')
        runner.run(repo, 'checkout', '-q', 'gnomish/PROJ-1')

        when:
        persistence.persist('PROJ-1', sampleState(), sampleTrace('implement', 0))

        then:
        thrown(RoundBoundaryViolationException)
    }

    def "FR12: HEAD off the task branch aborts persist"() {
        given:
        def persistence = new GitAttemptPersistence(runner, repo, 'PROJ-1')
        runner.run(repo, 'checkout', '-q', '-b', 'not-the-task-branch')

        when:
        persistence.persist('PROJ-1', sampleState(), sampleTrace('implement', 0))

        then:
        thrown(RoundBoundaryViolationException)
    }

    def "FR12: a gnome commit touching .gnomish-task/ aborts persist"() {
        given:
        def persistence = new GitAttemptPersistence(runner, repo, 'PROJ-1')
        new File(repo.toFile(), '.gnomish-task').mkdirs()
        new File(repo.toFile(), '.gnomish-task/tampered.txt').text = 'gnome should not write here'
        runner.run(repo, 'add', '.gnomish-task/tampered.txt')
        runner.run(repo, '-c', 'user.email=gnome@b.c', '-c', 'user.name=gnome', 'commit', '-m', 'gnome tampers')

        when:
        persistence.persist('PROJ-1', sampleState(), sampleTrace('implement', 0))

        then:
        thrown(RoundBoundaryViolationException)
    }

    def "FR11: with no origin remote configured, persist succeeds normally and pushes nothing"() {
        given:
        def persistence = new GitAttemptPersistence(runner, repo, 'PROJ-1')

        when:
        persistence.persist('PROJ-1', sampleState(), sampleTrace('implement', 0))

        then:
        noExceptionThrown()
        def log = runner.run(repo, 'log', '-1', '--format=%s')
        log.stdout().trim() == ServiceCommitMessages.round('implement', 0)
        runner.run(repo, 'remote', 'get-url', 'origin').exitCode() != 0
    }

    def "FR11: with origin configured, persist pushes the round commit to the remote's task branch"() {
        given:
        def bareRepo = initBareRepo(tempDir, 'origin.git')
        runner.run(repo, 'remote', 'add', 'origin', bareRepo.toString())
        def persistence = new GitAttemptPersistence(runner, repo, 'PROJ-1')

        when:
        persistence.persist('PROJ-1', sampleState(), sampleTrace('implement', 0))

        then:
        def localHead = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
        def remoteHead = runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().trim()
        remoteHead == localHead
    }

    def "FR11: a push failure does not throw and the local commit still lands"() {
        given: 'origin points at a path that is not a git repository at all, so push fails cleanly'
        def notARepo = tempDir.resolve('not-a-repo')
        notARepo.toFile().mkdirs()
        runner.run(repo, 'remote', 'add', 'origin', notARepo.toString())
        def persistence = new GitAttemptPersistence(runner, repo, 'PROJ-1')
        def headBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()

        when:
        persistence.persist('PROJ-1', sampleState(), sampleTrace('implement', 0))

        then:
        noExceptionThrown()
        def log = runner.run(repo, 'log', '--format=%H', "${headBefore}..HEAD")
        log.stdout().trim().readLines().size() == 1
    }
}
