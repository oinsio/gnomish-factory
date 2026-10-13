package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gittransfer.GitTransfer
import com.github.oinsio.gnomish.subprocess.Termination
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * The one in-process fake of {@link GitRunner} (design D27 of
 * supervise-daemon-loops-and-embed-dashboard; ADR 0015, decision 6): a spec of a class above the
 * runner scripts the {@link GitCommandResult} each git invocation returns, instead of handing
 * {@link GitProcessRunner} a stand-in binary that would cost a process per call per mutant.
 *
 * <p>Rows are tried in the order they were added; the first whose argv prefix matches answers. A
 * prefix is matched the way {@code stand-in.sh} matches a row: after the leading {@code -c
 * <name>=<value>} pairs are dropped, each word a glob ({@code *}, {@code ?}) against the argument at
 * its position; an empty prefix matches every invocation. An invocation no row matches throws,
 * naming its argv — the stand-in's exit 97, kept, so a changed argv shape fails the spec rather
 * than reaching a silent default. Every invocation is logged before it is answered, so a spec can
 * assert what ran and what never did.
 *
 * <p>A {@link #stalls} row blocks the calling thread until the spec releases its latch, and answers
 * {@link Termination#INTERRUPTED} when that thread is interrupted instead, with the flag restored —
 * a deterministic interrupt point for a termination spec, where a stand-in had a ten-minute sleep.
 *
 * <p>Kept in sync with {@code stand-in.sh} (the library of {@code :test-fixtures}): both drop the
 * leading {@code -c} pairs and match each prefix word as a glob at its position.
 */
final class ScriptedGit implements GitRunner {

    /** One invocation as the runner received it. */
    static final class Call {

        final Path cwd
        final List<String> argv

        Call(Path cwd, List<String> argv) {
            this.cwd = cwd
            this.argv = argv.asImmutable()
        }

        /** The argv with the leading {@code -c} pairs dropped: what a row's prefix is matched against. */
        List<String> command() {
            ScriptedGit.withoutConfigPairs(argv)
        }

        @Override
        String toString() {
            argv.join(' ')
        }
    }

    private static final class Row {

        final List<Pattern> prefix
        final Closure<GitCommandResult> answer

        Row(List<String> prefix, Closure<GitCommandResult> answer) {
            this.prefix = prefix.collect { ScriptedGit.glob(it) }
            this.answer = answer
        }

        boolean matches(List<String> command) {
            command.size() >= prefix.size() && (0..<prefix.size()).every {
                prefix[it].matcher(command[it]).matches()
            }
        }
    }

    private final List<Row> rows = new CopyOnWriteArrayList<>()
    private final List<Call> calls = new CopyOnWriteArrayList<>()

    /**
     * A git over a factory clone on the reftable ref backend: its first row answers the backend
     * check every touchpoint fetch makes before it writes ({@link FactoryCloneRefFormat}), so a
     * spec of a touchpoint scripts only what it is about.
     */
    static ScriptedGit reftableClone() {
        new ScriptedGit().answer([
            'rev-parse',
            '--show-ref-format'
        ], 0, FactoryCloneRefFormat.REQUIRED + '\n')
    }

    /** Answers every invocation starting with {@code prefix} with {@code result}. */
    ScriptedGit answer(List<String> prefix, GitCommandResult result) {
        rows << new Row(prefix, { result })
        this
    }

    /**
     * Answers every invocation starting with {@code prefix} with what {@code answer} computes when
     * the call arrives — for a remote whose answer the spec changes between calls (a push that
     * lands, an origin that stops answering).
     */
    ScriptedGit answer(List<String> prefix, Closure<GitCommandResult> answer) {
        rows << new Row(prefix, answer)
        this
    }

    /** Answers every invocation starting with {@code prefix} with an exit code and the two streams. */
    ScriptedGit answer(List<String> prefix, int exitCode, String stdout = '', String stderr = '') {
        answer(prefix, GitCommandResult.of(exitCode, stdout, stderr))
    }

    /**
     * Answers every invocation starting with {@code prefix} with the next result of {@code results}
     * in turn, the last one repeated: a push that fails once and then lands.
     */
    ScriptedGit answers(List<String> prefix, GitCommandResult... results) {
        List<GitCommandResult> queue = new CopyOnWriteArrayList<>(results.toList())
        rows << new Row(prefix, {
            queue.size() > 1 ? queue.removeFirst() : queue.first()
        })
        this
    }

    /**
     * Blocks every invocation starting with {@code prefix} until {@code release} opens, then answers
     * {@code released}; an interrupt of the blocked thread answers {@link Termination#INTERRUPTED}
     * at once, with the thread's flag restored, as the real runner does.
     */
    ScriptedGit stalls(List<String> prefix, CountDownLatch release, GitCommandResult released = GitCommandResult.of(0, '', '')) {
        rows << new Row(prefix, {
            try {
                release.await()
                released
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt()
                GitCommandResult.of(143, '', '', Termination.INTERRUPTED)
            }
        })
        this
    }

    @Override
    GitCommandResult run(Path cwd, String... args) {
        answerFor(new Call(cwd, args.toList()))
    }

    @Override
    GitCommandResult run(Path cwd, GitTransfer transfer) {
        answerFor(new Call(cwd, transfer.argv()))
    }

    /**
     * A ref transaction arrives as its argv, {@code update-ref --stdin}, followed by the script's
     * lines as further words — so a row can match the transaction by its prefix and a spec can
     * assert which refs it moved.
     */
    @Override
    GitCommandResult run(Path cwd, RefTransaction transaction) {
        answerFor(new Call(cwd, RefTransaction.ARGV + new String(transaction.script(), 'UTF-8').readLines()))
    }

    /** Every invocation so far, in order. */
    List<Call> calls() {
        calls.toList()
    }

    /** The invocations so far whose command starts with {@code prefix}; empty when none ran. */
    List<Call> calls(List<String> prefix) {
        Row probe = new Row(prefix, { null })
        calls.findAll { probe.matches(it.command()) }
    }

    /**
     * Returns once an invocation starting with {@code prefix} has arrived. Fails — after
     * interrupting {@code worker} so no thread is left blocked — when the worker ends first or ten
     * seconds pass: a defect that never reaches the call must fail the spec, not hang it.
     */
    void awaitCall(List<String> prefix, Thread worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (calls(prefix).isEmpty()) {
            if (!worker.alive || System.nanoTime() > deadline) {
                worker.interrupt()
                throw new AssertionError("git was never asked for '${prefix.join(' ')}' (worker ${worker.alive ? 'still running' : 'ended'})" as Object)
            }
            Thread.onSpinWait()
        }
    }

    private GitCommandResult answerFor(Call call) {
        calls << call
        Row row = rows.find { it.matches(call.command()) }
        if (row == null) {
            throw new AssertionError("ScriptedGit: no row matches: ${call}" as Object)
        }
        row.answer.call()
    }

    /** {@code argv} after its leading {@code -c <name>=<value>} pairs. */
    static List<String> withoutConfigPairs(List<String> argv) {
        int at = 0
        while (at + 1 <argv.size() && argv[at] == '-c') {
            at += 2
        }
        argv.drop(at)
    }

    /** A shell glob word as a pattern: {@code *} any run, {@code ?} one character, the rest literal. */
    static Pattern glob(String word) {
        Pattern.compile(word.collect { String c ->
            c == '*' ? '.*' : c == '?' ? '.' : Pattern.quote(c)
        }.join(''))
    }
}
