package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.DenialRead
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.GuardUnavailableException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test seam for the denial specs: the real round source with only its
 * environment's denial answer substituted, so what a spec drives is the
 * production round wiring rather than a hand-built stand-in for it.
 *
 * <p>Answers are consumed one per {@code readDenials()} call, standing in for
 * the guard's per-round delta read (D3): the second read sees the second round's
 * denials, never the first round's again. The last answer repeats once the script
 * is exhausted; a {@code null} answer stands for a read that cannot be served at
 * all (the daemon-outage shape) and throws.
 *
 * <p>{@code launchFails} additionally stands in for an environment that cannot start the
 * process at all — the third shape of a round that dies before its close, beside the
 * {@code roundTimeout} kill and the missing result event.
 *
 * <p>{@code guardDiesAtProbe} stands in for a guard that dies (D14 of tighten-spec-contract):
 * the guard probes are counted across every round of this source, and the probe with that
 * 0-based index and every later one throw the guard's own infrastructure failure. {@code null}
 * (the default) is a guard that never dies.
 */
final class ScriptedDenialRounds implements RoundEnvironmentSource {

    private final RoundEnvironmentSource delegate
    private final List<List<Finding>> answers
    private final boolean launchFails
    private final AtomicInteger reads = new AtomicInteger()
    private final AtomicInteger probes = new AtomicInteger()

    /** The 0-based index of the first guard probe that finds the guard dead; null: never. */
    Integer guardDiesAtProbe

    ScriptedDenialRounds(RoundEnvironmentSource delegate, List<List<Finding>> answers, boolean launchFails = false) {
        this.delegate = delegate
        this.answers = new ArrayList<>(answers)
        this.launchFails = launchFails
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

    private boolean launchFails() {
        launchFails
    }

    /** The number of guard probes so far, across every round opened from this source. */
    int probes() {
        probes.get()
    }

    private void probe() {
        int index = probes.getAndIncrement()
        if (guardDiesAtProbe != null && index >= guardDiesAtProbe) {
            throw new GuardUnavailableException('egress guard for k1 (container gnomish-guard-k1) is not running')
        }
    }

    @Override
    Round openRound(StageExecutor.Request request) {
        new ScriptedRound(delegate.openRound(request), this)
    }

    private static final class ScriptedRound implements Round {
        private final Round delegate
        private final ScriptedDenialRounds source

        ScriptedRound(Round delegate, ScriptedDenialRounds source) {
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
            if (source.launchFails()) {
                throw new ProcessStartException('no such binary', new IOException('boom'))
            }
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
        void probeGuard() {
            source.probe()
        }

        @Override
        DenialRead readDenials() {
            new DenialRead(source.nextAnswer().collect {
                Denial.unidentified(it)
            }, Optional.empty())
        }
    }
}
