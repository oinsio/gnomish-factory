package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path

/**
 * The host round source reduced to a recording stand-in: its rounds keep the seam's default no-op
 * listener and record whether the boundary hooks reached them, so a spec that wraps this source in
 * {@link MidRoundPushRounds} can assert the decorator replaces only the round listener and passes
 * every other {@code Round} method through untouched (FR2 of wire-host-mid-round-push).
 */
final class PassThroughRounds implements RoundEnvironmentSource {

    /** The round opened most recently — the handle a spec reads the recordings from. */
    FakeRound round

    @Override
    Round openRound(StageExecutor.Request request) {
        round = new FakeRound()
        round
    }

    /** One round: an identity-only environment plus the flags recording its boundary hooks. */
    static class FakeRound implements Round {

        /** Identity only — a spec asserts the decorator hands back this very instance. */
        final TaskExecutionEnvironment environment = [:] as TaskExecutionEnvironment
        boolean closed
        boolean discarded

        @Override
        TaskExecutionEnvironment environment() {
            environment
        }

        @Override
        Path decisionFilePath() {
            Path.of('decision.json')
        }

        @Override
        Map<String, String> decisionEnvFragment() {
            [GNOMISH_DECISION_FILE: 'decision.json']
        }

        @Override
        void closeRound() {
            closed = true
        }

        @Override
        Optional<String> readDecision() {
            Optional.of('decision-content')
        }

        @Override
        void discard() {
            discarded = true
        }
    }
}
