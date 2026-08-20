package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test seam for the denial specs: the real round source with only its
 * environment's denial answer substituted, so what a spec drives is the
 * production round wiring rather than a hand-built stand-in for it.
 *
 * <p>Answers are consumed one per {@code denialFindings()} call, standing in for
 * the guard's per-round delta read (D3): the second read sees the second round's
 * denials, never the first round's again. The last answer repeats once the script
 * is exhausted; a {@code null} answer stands for a read that cannot be served at
 * all (the daemon-outage shape) and throws.
 */
final class ScriptedDenialRounds implements RoundEnvironmentSource {

    private final RoundEnvironmentSource delegate
    private final List<List<Finding>> answers
    private final AtomicInteger reads = new AtomicInteger()

    ScriptedDenialRounds(RoundEnvironmentSource delegate, List<List<Finding>> answers) {
        this.delegate = delegate
        this.answers = new ArrayList<>(answers)
    }

    /** The number of denial reads so far, across every round opened from this source. */
    int reads() {
        reads.get()
    }

    private List<Finding> nextAnswer() {
        def answer = answers.get(Math.min(reads.getAndIncrement(), answers.size() - 1))
        if (answer == null) {
            throw new IllegalStateException('the guard log cannot be read')
        }
        answer
    }

    @Override
    Round openRound(StageExecutor.Request request) {
        new ScriptedRound(delegate.openRound(request), this)
    }

    private static final class ScriptedRound implements RoundEnvironmentSource.Round {
        private final RoundEnvironmentSource.Round delegate
        private final ScriptedDenialRounds source

        ScriptedRound(RoundEnvironmentSource.Round delegate, ScriptedDenialRounds source) {
            this.delegate = delegate
            this.source = source
        }

        @Override
        TaskExecutionEnvironment environment() {
            new ScriptedEnvironment(delegate.environment(), source)
        }

        @Override
        Path decisionFilePath() {
            delegate.decisionFilePath()
        }

        @Override
        Map<String, String> decisionEnvFragment() {
            delegate.decisionEnvFragment()
        }

        @Override
        AgentProgressListener roundListener() {
            delegate.roundListener()
        }

        @Override
        void closeRound() {
            delegate.closeRound()
        }

        @Override
        Optional<String> readDecision() {
            delegate.readDecision()
        }

        @Override
        void discard() {
            delegate.discard()
        }
    }

    /** A host environment answering the script's denials, standing in for a guarded box. */
    private static final class ScriptedEnvironment implements TaskExecutionEnvironment {
        private final TaskExecutionEnvironment delegate
        private final ScriptedDenialRounds source

        ScriptedEnvironment(TaskExecutionEnvironment delegate, ScriptedDenialRounds source) {
            this.delegate = delegate
            this.source = source
        }

        @Override
        void materialize(String branch, String commitPin) {
            delegate.materialize(branch, commitPin)
        }

        @Override
        ExecHandle exec(ExecCommand command) {
            delegate.exec(command)
        }

        @Override
        void putFile(String path, byte[] content) {
            delegate.putFile(path, content)
        }

        @Override
        Optional<byte[]> readFile(String path, long sizeCap) {
            delegate.readFile(path, sizeCap)
        }

        @Override
        void harvest() {
            delegate.harvest()
        }

        @Override
        void dispose() {
            delegate.dispose()
        }

        @Override
        String scratchRoot() {
            delegate.scratchRoot()
        }

        @Override
        CapabilityPassport passport() {
            delegate.passport()
        }

        @Override
        List<Finding> denialFindings() {
            source.nextAnswer()
        }
    }
}
