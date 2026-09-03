package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter.Vote;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one CLI judge round to a {@link Vote} (FR8, FR9, FR12, FR13, D5, D7 of add-agent-executor):
 * launches the process, drains and parses its stream-json stdout concurrently through the same
 * {@link StreamDrain} the executor round uses (FR6 of fix-round-stdout-drain), waits for exit
 * within {@code roundTimeout}, and grades the round's final message. Extracted from {@link
 * CliJudgeVoter} for file size.
 *
 * <p>Every exit that cannot produce a verdict passes through {@code cannotVerify}, which is
 * also the round's single WARN site: the vote's degraded result reaches the engine as a value,
 * so without a line here the six infrastructure failure classes (process start, round timeout,
 * wait interrupt, missing result event, drain timeout, drain interrupt) would be invisible to
 * the operator. One site, one line — the caller receives the same facts (FR5 of
 * harden-logging-observability).
 *
 * <p>Implements FR1, FR2, FR3, FR6, NFR-R1 of fix-round-stdout-drain; FR5 of
 * harden-logging-observability.
 */
final class JudgeRoundExecution {

    private static final Logger log = LoggerFactory.getLogger(JudgeRoundExecution.class);

    /** Shared by both {@code cannotVerify} overloads so the two lines cannot drift apart. */
    private static final String CANNOT_VERIFY_LINE = "judge vote cannot verify for criteria {}: {} ({})";

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
            return cannotVerify(check, "agent CLI process failed to start", factoryProperties.agentCliBinary(), e);
        }

        // Same shared drain as the executor round (FR6 of fix-round-stdout-drain), started
        // before the wait so the vote's stream is never bounded by the OS pipe buffer (FR1).
        try (StreamDrain drain = StreamDrain.start(launched.output(), clock, progressListener)) {
            Duration roundTimeout = RoundTimeout.resolve(check.settings());
            var wait = launched.waitForExitOrTimeout(roundTimeout, clock);
            if (wait instanceof ExecHandle.Wait.TimedOut) {
                // Still decided before the drain's events are consulted (FR3).
                return cannotVerify(
                        check, "agent round exceeded roundTimeout and was killed", "roundTimeout: " + roundTimeout);
            }
            if (wait instanceof ExecHandle.Wait.Interrupted) {
                // The never-throw contract again, and a cause of its own: blaming the budget for a
                // shutdown would send an operator to raise a number that was never the problem
                // (FR6, FR11 of bound-subprocess-commands).
                return cannotVerify(
                        check,
                        "agent round wait was interrupted and the process tree was killed",
                        "roundTimeout: " + roundTimeout);
            }

            List<TimestampedEvent> events = drain.await(factoryProperties.agentCliTailDrainGrace());
            Instant roundEnd = clock.now();
            AgentRoundResult roundResult = resultExtractor.extract(events, roundEnd, drain.bytesRead());
            Verdict verdict = verdictExtractor.extract(roundResult.result());
            return new Vote(verdict, roundResult.usage().tokensByModel());
        } catch (MissingResultEventException e) {
            return cannotVerify(check, "stream-json carried no result event for round", messageOf(e), e);
        } catch (StreamDrainTimeoutException e) {
            // The judge never throws (design D5 of add-agent-executor): an unfinished drain is
            // an infrastructure failure of the vote, reported as CannotVerify like a timeout.
            return cannotVerify(check, "agent stdout drain did not finish after process exit", messageOf(e), e);
        } catch (StreamDrainInterruptedException e) {
            // Same never-throw contract, different cause: the wait was cut short by an interrupt,
            // so the vote reports that rather than blaming the tail-drain grace.
            return cannotVerify(check, "interrupted while waiting for the agent stdout drain", messageOf(e), e);
        }
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** The two exits whose cause is a decision rather than a throwable (timeout, interrupt). */
    private static Vote cannotVerify(VerifyCheck.Judge check, String reason, String details) {
        log.warn(
                OperatorEvent.JUDGE_CANNOT_VERIFY_BY_DECISION.head() + CANNOT_VERIFY_LINE,
                check.criteriaFile(),
                reason,
                details);
        return new Vote(new Verdict.CannotVerify(reason, details), Map.of());
    }

    /** The four exits carrying a throwable, which is passed trailing so the stack survives. */
    private static Vote cannotVerify(VerifyCheck.Judge check, String reason, String details, Throwable cause) {
        log.warn(
                OperatorEvent.JUDGE_CANNOT_VERIFY_BY_THROWABLE.head() + CANNOT_VERIFY_LINE,
                check.criteriaFile(),
                reason,
                details,
                cause);
        return new Vote(new Verdict.CannotVerify(reason, details), Map.of());
    }
}
