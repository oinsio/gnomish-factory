package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.environment.ExecCommand;
import com.github.oinsio.gnomish.adapter.environment.ExecHandle;
import com.github.oinsio.gnomish.adapter.environment.ProcessStartException;
import com.github.oinsio.gnomish.adapter.environment.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter.Vote;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Runs one CLI judge round to a {@link Vote} (FR8, FR9, FR12, FR13, D5, D7 of add-agent-executor):
 * launches the process, waits for exit within {@code roundTimeout}, parses its stream-json
 * stdout, and grades the round's final message. Extracted from {@link CliJudgeVoter} for file
 * size; the behavior is unchanged.
 */
final class JudgeRoundExecution {

    private JudgeRoundExecution() {}

    static Vote run(
            FactoryProperties factoryProperties,
            Clock clock,
            AgentProgressListener progressListener,
            AgentRoundResultExtractor resultExtractor,
            JudgeVerdictExtractor verdictExtractor,
            VerifyCheck.Judge check,
            TaskExecutionEnvironment environment,
            String prompt) {
        var invocationFlags = AgentInvocationOptions.renderForJudge(check.model(), check.settings());
        List<String> command = AgentCommandLine.fromRenderedFlags(factoryProperties.agentCliBinary(), invocationFlags);

        ExecHandle launched;
        try {
            // Factory-set protocol layer (D6, FR9): a judge vote needs only the AI seam variables.
            launched = environment.exec(new ExecCommand(command, AgentAiSeam.fromFactoryEnvironment(), prompt, false));
        } catch (ProcessStartException e) {
            return cannotVerify("agent CLI process failed to start", factoryProperties.agentCliBinary());
        }

        Duration roundTimeout = RoundTimeout.resolve(check.settings());
        var wait = launched.waitForExitOrTimeout(roundTimeout, clock);
        if (wait instanceof ExecHandle.Wait.TimedOut) {
            return cannotVerify("agent round exceeded roundTimeout and was killed", "roundTimeout: " + roundTimeout);
        }

        // The process has already exited (or been killed) by this point, so its
        // stdout pipe is fully drained and reading it here cannot block indefinitely
        // (design D3, FR13) — same rationale as CliStageExecutor.
        List<TimestampedEvent> events = parseStdout(launched, clock, progressListener);
        Instant roundEnd = clock.now();
        try {
            AgentRoundResult roundResult = resultExtractor.extract(events, roundEnd);
            Verdict verdict = verdictExtractor.extract(roundResult.result());
            return new Vote(verdict, roundResult.usage().tokensByModel());
        } catch (MissingResultEventException e) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return cannotVerify("stream-json carried no result event for round", message);
        }
    }

    private static List<TimestampedEvent> parseStdout(
            ExecHandle launched, Clock clock, AgentProgressListener progressListener) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(launched.output(), StandardCharsets.UTF_8))) {
            return new StreamJsonParser(clock, progressListener).parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read agent process stdout", e);
        }
    }

    private static Vote cannotVerify(String reason, String details) {
        return new Vote(new Verdict.CannotVerify(reason, details), Map.of());
    }
}
