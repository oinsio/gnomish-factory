package com.github.oinsio.gnomish.adapter.agent.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultMapping
import com.github.oinsio.gnomish.adapter.agent.runner.RunnerFixture
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRoundAnswers
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerdictDerivation
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path

/**
 * A judge's {@code verdict} round on the live path (FR3, FR4 of extract-agent-round-runner): the
 * real {@link ClaudeDialect} under the agent round runner, its CLI a fake process whose stdout is the
 * stream the spec names, the result mapped onto the engine's shape by the one contract → domain
 * mapping and, where a spec wants the verdict, derived by the engine's one derivation. The streams
 * are a result event carrying a structured output, or one carrying only a final text (a CLI that
 * ignored {@code --json-schema}).
 *
 * <p>Test support; not production code, never PIT-mutated.
 */
final class ClaudeVerdictRounds {

    private static final String INIT = '{"type":"system","subtype":"init","session_id":"s","model":"claude-x"}\n'

    private ClaudeVerdictRounds() {}

    /** A runner fixture whose dialect is the real Claude dialect. */
    static RunnerFixture fixture(Path workspace, Path stderrParent) {
        new RunnerFixture(workspace: workspace, stderrParent: stderrParent,
        otherDialect: new ClaudeDialect('claude', UntrustedText.subprocess('2.1.0'), 'api.anthropic.com'))
    }

    /** A stream whose result event carries {@code output}, a JSON value, as its structured output. */
    static String structured(String output) {
        INIT + '{"type":"result","subtype":"success","session_id":"s","result":"","structured_output":' + output + '}\n'
    }

    /** A stream whose result event carries {@code text} as its final text and no structured output. */
    static String finalText(String text) {
        INIT + '{"type":"result","subtype":"success","session_id":"s","result":' + new ObjectMapper().writeValueAsString(text) + '}\n'
    }

    /** A stream whose result event reports the CLI's structured-output retries exhausted, with no output. */
    static String retriesExhausted() {
        INIT + '{"type":"result","subtype":"' + ClaudeAnswer.RETRIES_EXHAUSTED_SUBTYPE + '","session_id":"s","result":""}\n'
    }

    /** The engine's result of one verdict round whose CLI printed {@code stream}. */
    static ExecutionResult engine(RunnerFixture fixture, String stream, AttemptKey key = new AttemptKey('TASK-1', 'build', 0)) {
        def result = fixture.start(RunnerFixture.request(Role.VERDICT), RunnerFixture.exited(0, stream))
        ProgramResultMapping.toEngine(RunnerFixture.NAME, Role.VERDICT, key, result, [])
    }

    /** The verdict one verdict round whose CLI printed {@code stream} derives to. */
    static Verdict verdict(RunnerFixture fixture, String stream) {
        VerdictDerivation.derive(CheckRoundAnswers.of(engine(fixture, stream)), Threshold.DEFAULT)
    }

    /** Check rounds that each run one real verdict round over the next of {@code streams}. */
    static StageExecutor judges(RunnerFixture fixture, List<String> streams) {
        def left = new ArrayList<String>(streams)
        return { StageExecutor.Request request ->
            engine(fixture, left.removeFirst(),
            new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt()))
        } as StageExecutor
    }
}
