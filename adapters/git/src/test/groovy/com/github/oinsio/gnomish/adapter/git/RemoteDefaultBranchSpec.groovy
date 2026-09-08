package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5, FR9, NFR-P1 of add-base-ref-resolution: the zero-configuration tier of base resolution asks
 * origin which branch it calls its default, and every way that read can fail to produce a name
 * stays a distinct fact — a renamed default is followed, an absent origin refuses instead of
 * guessing, an empty repository is undetermined, and an unreachable origin is the one arm the
 * daemon is charged for.
 */
class RemoteDefaultBranchSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()

    /** Every wait the discovery spent, in order — the budget as elapsed virtual time. */
    private final VirtualSleeper sleeper = new VirtualSleeper(new VirtualClock())

    /** The production attempts and backoff, measured on virtual time rather than waited out. */
    private RemoteDefaultBranch discovery(GitProcessRunner boundRunner = runner) {
        new RemoteDefaultBranch(boundRunner, new GitInfrastructureRetry(sleeper,
                GitInfrastructureRetry.DEFAULT_ATTEMPTS, GitInfrastructureRetry.DEFAULT_INITIAL_BACKOFF))
    }

    /** A clone whose origin is a bare repo carrying one commit on {@code branch}, HEAD set to it. */
    private Path cloneOfRemoteDefaulting(String name, String branch) {
        Path work = initWorkingRepo(tempDir, "${name}-work")
        gitOutput(work, 'checkout', '-b', branch)
        commit(work, 'a.txt', 'seed')
        Path origin = initBareRepo(tempDir, "${name}-origin.git")
        addRemote(work, 'origin', origin.toString())
        assert gitExitCode(work, 'push', 'origin', branch) == 0
        assert gitExitCode(origin, 'symbolic-ref', 'HEAD', "refs/heads/${branch}") == 0
        work
    }

    def "FR5: the branch origin reports as its default is discovered"() {
        given:
        def clone = cloneOfRemoteDefaulting('plain', 'main')

        expect:
        discovery().discover(clone) == new DefaultBranchDiscovery.Discovered('main')
    }

    def "FR5: a renamed default branch is followed with no configuration change"() {
        given: 'a remote whose default was moved off main'
        def clone = cloneOfRemoteDefaulting('renamed', 'develop')

        when: 'the same zero-configuration read runs'
        def discovered = discovery().discover(clone)

        then: 'it names what origin holds now, never a hardcoded main'
        discovered == new DefaultBranchDiscovery.Discovered('develop')
    }

    def "FR5: the read goes to the remote, not to the clone's stale origin/HEAD symref"() {
        given: 'a clone whose local origin/HEAD still names the branch the remote has left behind'
        def clone = cloneOfRemoteDefaulting('stale', 'develop')
        assert gitExitCode(clone, 'symbolic-ref', 'refs/remotes/origin/HEAD', 'refs/remotes/origin/obsolete') == 0

        expect: 'the remote is the authority'
        discovery().discover(clone) == new DefaultBranchDiscovery.Discovered('develop')
    }

    def "FR5: a clone with no origin refuses instead of guessing a name"() {
        given:
        def clone = initWorkingRepo(tempDir, 'no-origin')
        commit(clone, 'a.txt', 'seed')

        when:
        def discovered = discovery().discover(clone)

        then: 'a settled fact, so the infrastructure budget is not spent on it'
        discovered == new DefaultBranchDiscovery.NoRemote()
        sleeper.slept.isEmpty()
    }

    def "FR5: an empty remote that names no default branch is undetermined, not unavailable"() {
        given: 'an origin with no commits, whose HEAD points at an unborn branch'
        def clone = initWorkingRepo(tempDir, 'empty-remote')
        commit(clone, 'a.txt', 'seed')
        addRemote(clone, 'origin', initBareRepo(tempDir, 'empty-origin.git').toString())

        when:
        def discovered = discovery().discover(clone)

        then: 'origin answered, so this is a fact about the repository — not an outage'
        discovered instanceof DefaultBranchDiscovery.Undetermined
        (discovered as DefaultBranchDiscovery.Undetermined).reason().contains('no default branch')
    }

    def "FR9: an unreachable origin is unavailable and names what the read did"() {
        given:
        def clone = initWorkingRepo(tempDir, 'dead-origin')
        commit(clone, 'a.txt', 'seed')
        addRemote(clone, 'origin', tempDir.resolve('does-not-exist.git').toString())

        when:
        def discovered = discovery().discover(clone)

        then: 'the infrastructure arm, and never a name'
        discovered instanceof DefaultBranchDiscovery.Unavailable
        (discovered as DefaultBranchDiscovery.Unavailable).reason().contains('default-branch read exited')
        !(discovered instanceof DefaultBranchDiscovery.NoRemote)
    }

    def "FR9: the unsettled read is re-asked under the infrastructure budget before giving up"() {
        given: 'a git whose ls-remote always fails, counting the invocations'
        Path log = tempDir.resolve('ls-remote-calls.log')
        Path gitBinary = tempDir.resolve('failing-git.sh')
        gitBinary.toFile().text = '''#!/bin/sh
for a in "$@"; do
  if [ "$a" = "ls-remote" ]; then echo "$@" >> "''' + log + '''"; echo "fatal: unable to access origin" >&2; exit 128; fi
done
exec git "$@"
'''
        gitBinary.toFile().executable = true
        def clone = initWorkingRepo(tempDir, 'retried')
        commit(clone, 'a.txt', 'seed')
        addRemote(clone, 'origin', tempDir.resolve('anywhere.git').toString())

        when:
        def discovered = discovery(new GitProcessRunner(gitBinary.toString())).discover(clone)

        then:
        discovered instanceof DefaultBranchDiscovery.Unavailable
        Files.readAllLines(log).size() == GitInfrastructureRetry.DEFAULT_ATTEMPTS

        and: 'the waits between them are the production backoff, doubling and bounded'
        sleeper.slept == [
            GitInfrastructureRetry.DEFAULT_INITIAL_BACKOFF,
            GitInfrastructureRetry.DEFAULT_INITIAL_BACKOFF.multipliedBy(2)
        ]
    }

    def "NFR-P1: a settled answer costs exactly one remote read"() {
        given:
        Path log = tempDir.resolve('argv.log')
        def clone = cloneOfRemoteDefaulting('counted', 'main')

        when:
        def discovered = discovery(new GitProcessRunner(recordingGit(log).toString())).discover(clone)

        then:
        discovered == new DefaultBranchDiscovery.Discovered('main')
        recordedSubcommands(log).count('ls-remote') == 1
    }

    def "NFR-S2: credentials in git's own output never reach the reported reason"() {
        given:
        def clone = initWorkingRepo(tempDir, 'token-origin')
        commit(clone, 'a.txt', 'seed')
        addRemote(clone, 'origin', 'https://ghp_SECRETTOKEN@127.0.0.1:1/owner/repo.git')

        when:
        def discovered = discovery().discover(clone)

        then:
        discovered instanceof DefaultBranchDiscovery.Unavailable
        !(discovered as DefaultBranchDiscovery.Unavailable).reason().contains('ghp_SECRETTOKEN')
    }

    def "the symref line is parsed, and output naming no head ref yields no name"() {
        expect:
        RemoteDefaultBranch.symrefBranch(stdout) == expected

        where:
        stdout || expected
        'ref: refs/heads/main\tHEAD\nabc123\tHEAD\n' || Optional.of('main')
        'ref: refs/heads/release/1.18\tHEAD\nabc\tHEAD\n' || Optional.of('release/1.18')
        'abc123\tHEAD\n' || Optional.empty()
        '' || Optional.empty()
        'ref: refs/tags/v1\tHEAD\n' || Optional.empty()
        'ref: refs/heads/\tHEAD\n' || Optional.empty()
    }
}
