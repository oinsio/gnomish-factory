package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5 of add-sandbox-core (the integration pass): after the strict sandboxed
 * persistence lands the round durably, the task branch is pushed best-effort
 * factory-side; a persist failure pushes nothing (durability first).
 */
class PushBestEffortAttemptPersistenceSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def "the push follows a successful persist, from the factory clone, for the task branch"() {
        given: 'a real clone with an origin, so the push is observable on the remote'
        def origin = initBareRepo(tempDir, 'origin')
        def cloneDir = tempDir.resolve('clone')
        def git = new GitProcessRunner()
        git.run(tempDir, 'clone', origin.toString(), cloneDir.toString())
        Files.writeString(cloneDir.resolve('a.txt'), 'a')
        git.run(cloneDir, 'add', 'a.txt')
        git.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        git.run(cloneDir, 'branch', 'gnomish/T-1')
        def delegate = Mock(AttemptPersistence)
        def persistence = new PushBestEffortAttemptPersistence(
                delegate, new BranchPush(git), cloneDir, 'gnomish/T-1')
        def trace = new ToolTrace(new AttemptKey('T-1', 'work', 0), [])

        when:
        persistence.persist('T-1', TaskState.atStageStart('work'), trace)

        then: 'the delegate persisted, and the branch reached origin'
        1 * delegate.persist('T-1', _, _)
        git.run(cloneDir, 'ls-remote', origin.toString(), 'refs/heads/gnomish/T-1')
                .stdout().trim()
    }

    def "a failed persist pushes nothing: durability is the branch state, never a stray push"() {
        given:
        def git = new GitProcessRunner()
        def delegate = Mock(AttemptPersistence)
        def persistence = new PushBestEffortAttemptPersistence(
                delegate, new BranchPush(git), tempDir, 'gnomish/T-1')

        when:
        persistence.persist('T-1', TaskState.atStageStart('work'),
                new ToolTrace(new AttemptKey('T-1', 'work', 0), []))

        then:
        1 * delegate.persist(_, _, _) >> {
            throw new GitPersistFailedException('T-1', 'work', 0, 'boom', 'x')
        }
        thrown(GitPersistFailedException)
    }
}
