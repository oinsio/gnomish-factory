package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1 of kill-expensive-mutants (design D1): the recording stand-in appends one block per
 * invocation, in invocation order, holding the record lines the caller named, and does not
 * record the subcommands it answers.
 */
class RecordingGitSpec extends Specification {

    @TempDir
    Path tempDir

    Path record

    def setup() {
        record = tempDir.resolve('record.txt')
    }

    def "FR1: two invocations read back as two blocks, in order"() {
        given:
        def runner = runnerOver(new RecordingGit(record).record('argv', '$*').record('marker', '[${RECORDING_GIT_UNSET-unset}]'))

        when:
        def first = runner.run(tempDir, 'version')
        def second = runner.run(tempDir, 'status', '--short')

        then:
        first.exitCode() == 0
        second.exitCode() == 0
        RecordingGit.blocks(record) == [
            [argv: 'version', marker: '[unset]'],
            [argv: 'status --short', marker: '[unset]'],
        ]
    }

    def "FR1: an answered subcommand prints its answer and leaves no block"() {
        given:
        def runner = runnerOver(new RecordingGit(record).answer('rev-parse', '.git').record('argv', '$*'))

        when:
        def answered = runner.run(tempDir, 'rev-parse', '--git-common-dir')
        runner.run(tempDir, 'version')

        then:
        answered.stdout().forParsing() == '.git\n'
        RecordingGit.blocks(record) == [[argv: 'version']]
    }

    def "FR1: a delayed stand-in takes at least its delay before it records"() {
        given:
        def runner = runnerOver(new RecordingGit(record).delay(Duration.ofMillis(100)).record('argv', '$*'))

        when:
        long started = System.nanoTime()
        def result = runner.run(tempDir, 'version')
        long elapsed = System.nanoTime() - started

        then:
        result.exitCode() == 0
        elapsed >= Duration.ofMillis(100).toNanos()
        RecordingGit.blocks(record) == [[argv: 'version']]
    }

    private GitProcessRunner runnerOver(RecordingGit git) {
        new GitProcessRunner(git.write(tempDir).toString())
    }
}
