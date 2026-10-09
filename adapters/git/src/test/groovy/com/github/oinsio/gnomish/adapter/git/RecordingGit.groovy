package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The recording stand-in {@code git}: a shell script that answers the subcommands the caller
 * names, and for every other invocation appends one block to a record file — the record lines the
 * caller names, each a key and the shell expression it prints — then exits 0. A spec observes
 * what the runner handed the child (argv, environment) by reading the blocks back; the
 * environment half is only observable from inside the child.
 *
 * <p>Answered subcommands are not recorded: the runner resolves a mutating command's clone key
 * through a local {@code rev-parse} on the same binary first, and the record is meant to hold the
 * command under test only. The argv is not stripped of its leading {@code -c} pairs — they are
 * part of what a spec asserts on.
 *
 * <p>An optional {@link #delay} makes every recorded invocation take that long before its block
 * is written. The delay is not spelled here: it is a stand-in built by {@link StallingGit}, the
 * one owner of a script that waits, which this script runs before recording.
 *
 * <p>Extracted from {@code GitProcessRunnerTransferSpec.recordingRunner()}, which builds through
 * it. Implements FR1 of kill-expensive-mutants (design D1).
 */
class RecordingGit {

    /** The line that closes every block, so consecutive invocations read back apart. */
    static final String END_OF_BLOCK = '--'

    private final Path record
    private final List<String> answers = []
    private final List<String> recordLines = []
    private Duration delay = Duration.ZERO

    RecordingGit(Path record) {
        this.record = record
    }

    /** Answers an invocation whose first argument is {@code subcommand} with {@code stdout}, exit 0, unrecorded. */
    RecordingGit answer(String subcommand, String stdout) {
        answers << "if [ \"\$1\" = ${StallingGit.quote(subcommand)} ]; then printf '%s\\n' ${StallingGit.quote(stdout)}; exit 0; fi".toString()
        this
    }

    /**
     * Adds the record line {@code key=<value>}, where the value is {@code shellExpression}
     * expanded inside double quotes by the child ({@code $*}, {@code ${GIT_SSH_COMMAND-unset}}).
     */
    RecordingGit record(String key, String shellExpression) {
        recordLines << "  echo \"${key}=${shellExpression}\"".toString()
        this
    }

    /** How long every recorded invocation takes before its block is written (zero by default). */
    RecordingGit delay(Duration length) {
        delay = length
        this
    }

    /** Writes the stand-in into {@code dir} under a fresh name and returns its executable path. */
    Path write(Path dir) {
        List<String> lines = ['#!/bin/sh']
        lines.addAll(answers)
        if (!delay.isZero()) {
            Path waiting = new StallingGit().stallOnEverything().stall(delay).write(dir)
            lines << StallingGit.quote(waiting.toString())
        }
        lines << '{'
        lines.addAll(recordLines)
        lines << "  echo ${StallingGit.quote(END_OF_BLOCK)}".toString()
        lines << "} >> ${StallingGit.quote(record.toString())}".toString()
        lines << 'exit 0'
        Path file = Files.createTempFile(dir, 'recording-git-', '.sh')
        file.toFile().text = lines.join('\n') + '\n'
        file.toFile().setExecutable(true)
        file
    }

    /** The blocks of {@code record}, in invocation order, each as its record lines keyed by key. */
    static List<Map<String, String>> blocks(Path record) {
        List<Map<String, String>> blocks = []
        Map<String, String> current = [:]
        record.toFile().readLines().each { String line ->
            if (line == END_OF_BLOCK) {
                blocks << current
                current = [:]
            } else {
                int at = line.indexOf('=')
                current[line.substring(0, at)] = line.substring(at + 1)
            }
        }
        blocks
    }
}
