package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The one builder of a stalling stand-in {@code git}: a shell script that sleeps on a chosen set
 * of subcommands, answers every other one from a declared table, and may make every answer take
 * a local-command delay. Owns the stall mechanics — strip the leading {@code -c} pairs, stall on
 * the set, answer the rest — so a spec states only its scenario.
 *
 * <p>Local commands run before the remote one: {@code GitProcessRunner} resolves a mutating
 * command's clone key through a local {@code rev-parse --git-common-dir} on the same binary,
 * unbounded by requirement, so a stand-in that sleeps on every subcommand sleeps out its whole
 * stall there, before the bounded command even starts — which is why a spec stalls on the
 * subcommands it means ({@link #stallOn}) and lets the rest answer at once.
 *
 * <p>History: before this builder the same mechanics were spelled by hand nine times over —
 * private helpers and scenario scripts across {@code :adapters:git}'s test tree (two of them an
 * undeclared copy of each other) and the declared pair {@code StallingGitFixture} /
 * {@code StallingReadGitFixture} — each choosing its own stall set, and one that slept on
 * everything turned a 2 s deadline proof into a 62.5 s one.
 *
 * <p>Matching: a stalled subcommand wins over every answer; answers are tried in declaration
 * order against the argv prefix after the {@code -c} pairs, first match wins; an unlisted
 * subcommand exits 0 with no output, so an unanswered clone-key {@code rev-parse} makes the clone
 * key fall back to the working directory. {@link #localDelay} delays every invocation that does
 * not stall <em>except</em> the clone-key resolution itself: that one is the runner's own
 * bookkeeping in front of a network command, and delaying it would delay the deadline under test.
 *
 * <p>Implements FR2, UX2 of kill-expensive-mutants (design D4).
 */
class StallingGit {

    /** Long enough that a stalled command can only end on a deadline or an interrupt. */
    static final Duration DEFAULT_STALL = Duration.ofSeconds(600)

    private final Set<String> stalled = new LinkedHashSet<>()
    private boolean stallsOnEverything
    private Duration stall = DEFAULT_STALL
    private final List<String> beforeStall = []
    private final List<String> answers = []
    private Duration localDelay = Duration.ZERO

    StallingGit stallOn(String... subcommands) {
        stalled.addAll(subcommands)
        this
    }

    StallingGit stallOnEverything() {
        stallsOnEverything = true
        this
    }

    StallingGit stall(Duration length) {
        stall = length
        this
    }

    /** A shell line run, in declaration order, at the start of every stall, before its sleep. */
    StallingGit beforeStall(String shellLine) {
        beforeStall << shellLine
        this
    }

    /** {@code marker} appears once a stall has begun; a {@link #beforeStall} of a {@code touch}. */
    StallingGit markOnStall(Path marker) {
        beforeStall("touch ${quote(marker.toString())}")
    }

    /**
     * Answers an argv starting with {@code argvPrefix} with {@code stdout} (printed verbatim plus
     * one newline; nothing when empty) and {@code exit}.
     */
    StallingGit answer(List<String> argvPrefix, String stdout, int exit) {
        if (argvPrefix.isEmpty()) {
            throw new IllegalArgumentException('an answer needs at least the subcommand')
        }
        String print = stdout ? "printf '%s\\n' ${quote(stdout)}; " : ''
        addAnswer(argvPrefix, "${print}exit ${exit}")
    }

    StallingGit answer(String subcommand, String stdout, int exit) {
        answer([subcommand], stdout, exit)
    }

    /**
     * Answers {@code subcommand} by running {@code shellFragment} when invoked — for an answer
     * read from a file the spec rewrites after {@link #write}. It is not a second way to spell a
     * stall: the owner spec's {@code sleep} scan still catches a stall written through it. A
     * fragment that does not exit itself ends with its last command's status.
     */
    StallingGit answerWith(String subcommand, String shellFragment) {
        addAnswer([subcommand], "${shellFragment}\n  exit \$?")
    }

    /** How long every invocation that does not stall takes (zero by default; see class doc). */
    StallingGit localDelay(Duration delay) {
        localDelay = delay
        this
    }

    /** Writes the stand-in into {@code dir} under a fresh name and returns its executable path. */
    Path write(Path dir) {
        Path file = Files.createTempFile(dir, 'stalling-git-', '.sh')
        file.toFile().text = script()
        file.toFile().setExecutable(true)
        file
    }

    private String script() {
        List<String> lines = [
            '#!/bin/sh',
            'while [ "$1" = "-c" ]; do shift 2; done'
        ]
        String stallBody = (beforeStall + [
            "sleep ${seconds(stall)}; exit 0".toString()
        ]).join('\n  ')
        if (stallsOnEverything) {
            lines << stallBody
        } else if (!stalled.isEmpty()) {
            lines << "case \"\$1\" in\n  ${stalled.collect { quote(it) }.join('|')})\n  ${stallBody} ;;\nesac".toString()
        }
        if (!localDelay.isZero()) {
            lines << "if ! { ${condition(['rev-parse', '--git-common-dir'])}; }; then sleep ${seconds(localDelay)}; fi".toString()
        }
        lines.addAll(answers)
        lines << 'exit 0'
        lines.join('\n') + '\n'
    }

    private StallingGit addAnswer(List<String> argvPrefix, String body) {
        answers << "if ${condition(argvPrefix)}; then\n  ${body}\nfi".toString()
        this
    }

    private static String condition(List<String> argvPrefix) {
        argvPrefix.withIndex().collect { String arg, int i ->
            "[ \"\${${i + 1}}\" = ${quote(arg)} ]"
        }.join(' && ')
    }

    private static String seconds(Duration duration) {
        String.format('%d.%03d', duration.toSeconds(), duration.toMillisPart())
    }

    private static String quote(String text) {
        "'" + text.replace("'", "'\\''") + "'"
    }
}
