package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR12, design D13 of add-base-ref-resolution: the resume-time base resolution behind {@link
 * com.github.oinsio.gnomish.app.port.git.BaseRefGit#resolveForResume}. A remote-configured clone
 * narrow-fetches the pinned ref exactly as {@link BaseRefresh} does; a clone with no {@code origin}
 * at all reads the ref's local tip instead of refusing; a ref that resolves nowhere either way is a
 * deterministic refusal; a configured origin that never answers is the infrastructure class.
 */
class ResumeBaseResolutionSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()
    private final VirtualSleeper sleeper = new VirtualSleeper(new VirtualClock())

    private Path origin
    private Path work
    private Path clone

    def setup() {
        work = initWorkingRepo(tempDir, 'work')
        gitOutput(work, 'checkout', '-b', 'main')
        commit(work, 'a.txt', 'one')
        gitOutput(work, 'checkout', '-b', 'develop')
        commit(work, 'b.txt', 'two')
        origin = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', origin.toString())
        assert gitExitCode(work, 'push', 'origin', 'main', 'develop') == 0
        assert gitExitCode(tempDir, 'clone', origin.toString(), 'clone') == 0
        clone = tempDir.resolve('clone')
    }

    private ResumeBaseResolution resolution(GitProcessRunner boundRunner = runner) {
        new ResumeBaseResolution(boundRunner, new GitInfrastructureRetry(sleeper,
                GitInfrastructureRetry.DEFAULT_ATTEMPTS, GitInfrastructureRetry.DEFAULT_INITIAL_BACKOFF))
    }

    private String advance(String branch, String file) {
        gitOutput(work, 'checkout', branch)
        commit(work, file, 'more')
        assert gitExitCode(work, 'push', 'origin', branch) == 0
        gitOutput(work, 'rev-parse', 'HEAD')
    }

    // FR12: a remote-configured clone narrow-fetches the pinned ref to origin's current tip,
    // exactly the base-refresh fetch — reuse, not a second implementation.
    def "a configured origin narrow-fetches the pinned ref to its current tip"() {
        given:
        def advanced = advance('develop', 'c.txt')

        when:
        def outcome = resolution().resolve(clone, 'develop')

        then:
        outcome == new ResumeBaseOutcome.Bound('develop', advanced)
    }

    // D13: a pinned ref that resolves nowhere on a configured origin parks the task.
    def "a configured origin refuses a pinned ref it holds in neither namespace"() {
        when:
        def outcome = resolution().resolve(clone, 'no-such-base')

        then:
        outcome instanceof ResumeBaseOutcome.Refused
        (outcome as ResumeBaseOutcome.Refused).report().contains('no-such-base')
    }

    // D9, D13: a configured origin that never answers releases the claim, never parks the task.
    def "a configured origin that never answers is the infrastructure class"() {
        given:
        assert gitExitCode(clone, 'remote', 'set-url', 'origin', tempDir.resolve('gone.git').toString()) == 0

        when:
        def outcome = resolution().resolve(clone, 'develop')

        then:
        outcome instanceof ResumeBaseOutcome.Unavailable
    }

    // D13: manual resume in a clone with no origin at all binds from the ref's LOCAL tip instead
    // of refusing — a resume with nothing to fetch from is a legitimate shape.
    def "a clone with no origin binds from the ref's local tip"() {
        given:
        assert gitExitCode(clone, 'branch', 'release/1.18', 'refs/remotes/origin/develop') == 0
        def localTip = gitOutput(clone, 'rev-parse', 'release/1.18')
        assert gitExitCode(clone, 'remote', 'remove', 'origin') == 0

        when:
        def outcome = resolution().resolve(clone, 'release/1.18')

        then:
        outcome == new ResumeBaseOutcome.Bound('release/1.18', localTip)
    }

    // D13: a clone with no origin AND a ref that resolves nowhere locally either parks the task —
    // never a silent fall-back to the pinned SHA.
    def "a clone with no origin and no local resolution refuses"() {
        given:
        assert gitExitCode(clone, 'remote', 'remove', 'origin') == 0

        when:
        def outcome = resolution().resolve(clone, 'release/does-not-exist')

        then:
        outcome instanceof ResumeBaseOutcome.Refused
        (outcome as ResumeBaseOutcome.Refused).report().contains('release/does-not-exist')

        and: 'no network was even attempted'
        sleeper.slept.isEmpty()
    }

    // D13: a SHA already present in the clone's history costs no network, remote configured or
    // not — reusing BaseRefresh's own commit fast path.
    def "a commit base the clone already holds costs no network at all"() {
        given:
        def held = gitOutput(clone, 'rev-parse', 'refs/remotes/origin/develop')
        Path log = tempDir.resolve('sha-present.log')

        when:
        def outcome = resolution(new GitProcessRunner(recordingGit(log).toString())).resolve(clone, held)

        then:
        outcome == new ResumeBaseOutcome.Bound(held, held)

        and:
        !recordedSubcommands(log).contains('fetch')
        !recordedSubcommands(log).contains('ls-remote')
    }
}
