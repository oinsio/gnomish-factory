package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter.Vote;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;

/**
 * Runs one CLI judge round to a {@link Vote} (FR8, FR9, FR12, FR13, D5, D7 of add-agent-executor):
 * launches the process, drains and parses its stream-json stdout concurrently through the same
 * {@link StreamDrain} the executor round uses (FR6 of fix-round-stdout-drain), waits for exit
 * within {@code roundTimeout}, and grades the round's final message. Extracted from {@link
 * CliJudgeVoter} for file size.
 *
 * <p>Every exit that cannot produce a verdict passes through {@link JudgeCannotVerify}, the
 * round's single WARN site, which also carries the tail of the CLI's stderr into the vote's
 * details on every exit after the launch (design D18 of fix-terminal-receipt-convergence, as
 * amended 2026-10-17); a vote that was graded leaves the tail as one DEBUG line.
 *
 * <p>Kept in sync with {@link ExecutorRoundExecution}: both launch one agent round from the same
 * {@link AgentRoundEquipment} — the CLI binary through the environment port with the factory-set
 * AI seam variables and the CLI's stderr sent to a {@link RoundScratchDirectory} that leaves with
 * the round, a {@link StreamDrain} started before the wait, the wait bounded by {@code
 * roundTimeout} with timeout and interrupt classified before the drain's events are consulted,
 * then the handle's {@link ExecHandle#postExitBound()} for the tail, never a bound of its own —
 * and each maps every failure of that launch sequence through one site that carries the stderr
 * tail of the round (here: {@link JudgeCannotVerify}; there: {@code ExecutorFailure}), leaving it
 * one DEBUG line on success. Both probe the environment's egress guard before
 * the launch and again once the process has exited, before anything is graded (D14 of
 * tighten-spec-contract).
 *
 * <p>Implements FR1, FR2, FR3, FR6, NFR-R1 of fix-round-stdout-drain; FR5 of
 * harden-logging-observability; FR6 of add-parameter-count-gate (design D6, Sync surfaces);
 * NFR-R1 of add-sandbox-core as probed per round by D14 of tighten-spec-contract; FR19 of
 * fix-terminal-receipt-convergence (design D17 as amended 2026-10-17: the post-exit wait is the
 * handle's bound); FR20 (design D18: the CLI's stderr goes to the round scratch directory).
 */
final class JudgeRoundExecution {

    private JudgeRoundExecution() {}

    static Vote run(
            AgentRoundEquipment equipment,
            JudgeVerdictExtractor verdictExtractor,
            VerifyCheck.Judge check,
            TaskExecutionEnvironment environment,
            String prompt) {
        var invocationFlags = AgentInvocationOptions.renderForJudge(check.model(), check.settings());
        List<String> command = AgentCommandLine.fromRenderedFlags(
                AgentRole.JUDGE, equipment.factoryProperties().agentCliBinary(), invocationFlags);

        // D14 of tighten-spec-contract: a guard dead since the last vote of the box fails this one.
        JudgeCannotVerify beforeRound = JudgeCannotVerify.beforeRound(check);
        Optional<Vote> deadBefore = beforeRound.ifGuardDead(environment);
        if (deadBefore.isPresent()) {
            return deadBefore.get();
        }
        // D18 of fix-terminal-receipt-convergence: the CLI's stderr goes to the round scratch directory.
        RoundScratchDirectory scratch;
        try {
            scratch = RoundScratchDirectory.open(equipment.roundScratchParent());
        } catch (UncheckedIOException e) {
            var cause = UntrustedText.factory(String.valueOf(e.getMessage()));
            return beforeRound.byThrowable("the round scratch directory could not be created", cause, e);
        }
        try (scratch) {
            // Factory-set protocol layer (D6, FR9): a judge vote needs only the AI seam variables.
            var exec = new ExecCommand(command, AgentAiSeam.variables(equipment.childEnv()), prompt, scratch.stderr());
            return launchAndGrade(equipment, verdictExtractor, check, environment, exec, scratch);
        }
    }

    private static Vote launchAndGrade(
            AgentRoundEquipment equipment,
            JudgeVerdictExtractor verdictExtractor,
            VerifyCheck.Judge check,
            TaskExecutionEnvironment environment,
            ExecCommand command,
            RoundScratchDirectory scratch) {
        InstantSource clock = equipment.clock();
        // Every exit from here on ran or tried to run the CLI, so it carries the stderr's tail.
        JudgeCannotVerify cannotVerify = JudgeCannotVerify.ofRound(check, scratch);
        ExecHandle launched;
        try {
            launched = environment.exec(command);
        } catch (ProcessStartException e) {
            var binary = UntrustedText.factory(equipment.factoryProperties().agentCliBinary());
            return cannotVerify.byThrowable("agent CLI process failed to start", binary, e);
        }

        // Same shared drain as the executor round (FR6 of fix-round-stdout-drain), started
        // before the wait so the vote's stream is never bounded by the OS pipe buffer (FR1).
        try (StreamDrain drain = StreamDrain.start(launched.output(), clock, equipment.progressListener())) {
            Duration roundTimeout = RoundTimeout.resolve(check.settings());
            var wait = launched.waitForExitOrTimeout(roundTimeout, clock);
            if (wait instanceof ExecHandle.Wait.TimedOut) {
                // Still decided before the drain's events are consulted (FR3).
                return cannotVerify.byDecision(
                        "agent round exceeded roundTimeout and was killed",
                        UntrustedText.factory("roundTimeout: " + roundTimeout));
            }
            if (wait instanceof ExecHandle.Wait.Interrupted) {
                // A cause of its own, never the budget's (FR6, FR11 of bound-subprocess-commands).
                return cannotVerify.byDecision(
                        "agent round wait was interrupted and the process tree was killed",
                        UntrustedText.factory("roundTimeout: " + roundTimeout));
            }
            // D14 of tighten-spec-contract: a guard that died mid-round broke the judge's cage, so
            // the vote cannot verify rather than count.
            Optional<Vote> deadAtClose = cannotVerify.ifGuardDead(environment);
            if (deadAtClose.isPresent()) {
                return deadAtClose.get();
            }

            List<TimestampedEvent> events = drain.await(launched.postExitBound());
            Instant roundEnd = clock.instant();
            AgentRoundResult roundResult = equipment.resultExtractor().extract(events, roundEnd, drain.bytesRead());
            Verdict verdict = verdictExtractor.extract(roundResult.result());
            scratch.logTailOnSuccess();
            return new Vote(verdict, roundResult.usage().tokensByModel());
        } catch (MissingResultEventException e) {
            return cannotVerify.byThrowable(
                    "stream-json carried no result event for round", JudgeCannotVerify.messageOf(e), e);
        } catch (StreamDrainTimeoutException e) {
            // The judge never throws (design D5 of add-agent-executor): an unfinished drain cannot verify.
            return cannotVerify.byThrowable(
                    "agent stdout drain did not finish after process exit", JudgeCannotVerify.messageOf(e), e);
        } catch (StreamDrainInterruptedException e) {
            // Same contract, its own cause: an interrupt, never the post-exit bound.
            return cannotVerify.byThrowable(
                    "interrupted while waiting for the agent stdout drain", JudgeCannotVerify.messageOf(e), e);
        }
    }
}
