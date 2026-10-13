package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5 of add-sandbox-core (the integration pass): after the strict sandboxed
 * persistence lands the round durably, the task branch and its factory register
 * are pushed best-effort factory-side, atomically (FR13 of tighten-spec-contract);
 * a persist failure pushes nothing (durability first).
 */
class PushBestEffortAttemptPersistenceSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def "the push follows a successful persist, from the factory clone, for the task branch"() {
        given: 'a real clone with an origin, so the push is observable on the remote'
        def cloneDir = initTaskWorkingRepo(tempDir, 'T-1')
        def origin = initBareRepo(tempDir, 'origin')
        addRemote(cloneDir, 'origin', origin.toString())
        def git = new GitProcessRunner()
        def delegate = Mock(AttemptPersistence)
        def persistence = new PushBestEffortAttemptPersistence(delegate, new BestEffortClosingPush(git), cloneDir)
        def trace = new ToolTrace(new AttemptKey('T-1', 'work', 0), [])

        when:
        persistence.persist('T-1', TaskState.atStageStart('work'), trace)

        then: 'the delegate persisted — its admission landed branch and register together — and both reached origin'
        1 * delegate.persist('T-1', _, _) >> {
            git.run(cloneDir, 'update-ref', 'refs/gnomish-factory/T-1', 'refs/heads/gnomish/T-1')
        }
        def landed = git.run(cloneDir, 'ls-remote', origin.toString(), 'refs/heads/gnomish/T-1')
                .stdout().forParsing().trim()
        landed

        and: 'FR13 of tighten-spec-contract: the state commit is a closing commit — its register travelled with it'
        git.run(cloneDir, 'ls-remote', origin.toString(), 'refs/gnomish-factory/T-1').stdout().forParsing().trim()
                .split('\t')[0] == landed.split('\t')[0]
    }

    def "a failed persist pushes nothing: durability is the branch state, never a stray push"() {
        given:
        def git = new GitProcessRunner()
        def delegate = Mock(AttemptPersistence)
        def persistence = new PushBestEffortAttemptPersistence(delegate, new BestEffortClosingPush(git), tempDir)

        when:
        persistence.persist('T-1', TaskState.atStageStart('work'),
                new ToolTrace(new AttemptKey('T-1', 'work', 0), []))

        then:
        1 * delegate.persist(_, _, _) >> {
            throw new GitPersistFailedException('T-1', 'work', 0, 'boom', UntrustedText.subprocess('x'))
        }
        thrown(GitPersistFailedException)
    }
}
