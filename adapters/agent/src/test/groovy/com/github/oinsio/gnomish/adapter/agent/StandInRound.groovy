package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.agent.TerminalClose
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path

/**
 * An open round reduced to what a launch-level spec of an agent stage round reaches: its environment
 * and answer path, with a close that does nothing. Discarded at most once.
 */
class StandInRound implements RoundEnvironmentSource.Round {

    private final TaskExecutionEnvironment environment
    private final Path answerFile

    StandInRound(TaskExecutionEnvironment environment, Path answerFile) {
        this.environment = environment
        this.answerFile = answerFile
    }

    /** A round source opening a {@link StandInRound} over {@code environment} for every round. */
    static RoundEnvironmentSource over(TaskExecutionEnvironment environment, Path answerFile) {
        [openRound: { request -> new StandInRound(environment, answerFile) },
            identity: { RoundIdentity.inPlace() }] as RoundEnvironmentSource
    }

    @Override
    TaskExecutionEnvironment environment() {
        environment
    }

    @Override
    Path answerFilePath() {
        answerFile
    }

    @Override
    Map<String, String> answerEnvFragment() {
        [:]
    }

    @Override
    void goPending() {
    }

    @Override
    void closeRound(TerminalClose close) {
    }

    /** Whether the round was discarded; a failed round is, exactly once. */
    boolean discarded

    @Override
    void discard() {
        assert !discarded
        discarded = true
    }
}
