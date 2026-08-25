package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.subprocess.Termination
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR5, NFR-R1, NFR-R3, M1 of bound-subprocess-commands: a git command that talks to a remote
 * is bounded by the runner's configured deadline and reports a named {@code TIMED_OUT} outcome; a
 * local command against the very same stalling binary is not bounded at all (NG3 — a local git
 * that hangs is a broken machine, not a broken remote); and a command that finishes normally keeps
 * its exit code, stdout and stderr exactly as before.
 *
 * <p>Driven through the runner's git-binary seam: a stand-in that answers {@code rev-parse}
 * instantly — the runner resolves a mutating command's clone key through it before running the
 * command itself — and then stalls for far longer than any deadline this spec configures.
 */
class GitProcessRunnerBoundedNetworkSpec extends Specification implements BareGitRepoFixture {

    /** Long enough that a wall-clock assertion can only pass because the deadline fired. */
    static final String STALL_SECONDS = '600'

    @TempDir
    Path tempDir

    def "FR1, NFR-R1, M1: a network command against a silent remote ends on its deadline, not on the remote"() {
        given: 'a git that answers rev-parse instantly and then stalls on push'
        def runner = new GitProcessRunner(stallingGit().toString(), Duration.ofSeconds(2))

        when:
        def started = System.nanoTime()
        def result = runner.run(tempDir, 'push', 'origin', 'HEAD')
        def elapsed = Duration.ofNanos(System.nanoTime() - started)

        then: 'the outcome names the timeout rather than dressing it up as a git exit code'
        result.termination() == Termination.TIMED_OUT

        and: 'M1: the wall clock is the deadline plus a kill, not the stall'
        elapsed <Duration.ofSeconds(4)
    }

    def "FR1, NG3: a local command against the same stalling binary is not bounded"() {
        given: 'a deadline far shorter than the local command takes to answer'
        def runner = new GitProcessRunner(stallingGit().toString(), Duration.ofMillis(200))

        when: 'a local subcommand — the stand-in sleeps a second, then exits normally'
        def result = runner.run(tempDir, 'status')

        then: 'it ran to its own exit; the network deadline never applied to it'
        result.termination() == Termination.EXITED
        result.exitCode() == 0
        result.stdout().trim() == 'local done'
    }

    def "NFR-R3: a network command that answers normally is byte-identical to before, and EXITED"() {
        given: 'a real bare repo reachable over a local path, so ls-remote is a genuine network-class command'
        def origin = initBareRepo(tempDir)
        def runner = new GitProcessRunner('git', Duration.ofSeconds(30))

        when:
        def result = runner.run(tempDir, 'ls-remote', origin.toString())

        then:
        result.termination() == Termination.EXITED
        result.exitCode() == 0
        result.stdout().isEmpty()
    }

    def "FR3, NFR-S2: the partial stderr of a killed network command is still scrubbed"() {
        given: 'a git that prints a PAT-bearing remote URL to stderr and then never exits'
        def fakeGit = tempDir.resolve('leaky-git')
        fakeGit.toFile().text = """#!/bin/sh
while [ "\$1" = "-c" ]; do shift 2; done
if [ "\$1" = "rev-parse" ]; then echo ".git"; exit 0; fi
echo "fatal: could not read Password for 'https://ghp_FAKETOKEN1234567890@github.com'" >&2
sleep ${STALL_SECONDS}
"""
        fakeGit.toFile().executable = true

        when:
        def result = new GitProcessRunner(fakeGit.toString(), Duration.ofSeconds(2)).run(tempDir, 'push')

        then: 'the scrub is the one choke point, on the kill path as much as on the normal one'
        result.termination() == Termination.TIMED_OUT
        !result.stderr().contains('ghp_FAKETOKEN1234567890')
        result.stderr().contains("could not read Password for 'https://***@github.com'")
    }

    private Path stallingGit() {
        def fakeGit = tempDir.resolve('stalling-git')
        fakeGit.toFile().text = """#!/bin/sh
while [ "\$1" = "-c" ]; do shift 2; done
case "\$1" in
  rev-parse) echo ".git"; exit 0 ;;
  push|fetch|ls-remote|clone) sleep ${STALL_SECONDS} ;;
  *) sleep 1; echo "local done"; exit 0 ;;
esac
"""
        fakeGit.toFile().executable = true
        return fakeGit
    }
}
