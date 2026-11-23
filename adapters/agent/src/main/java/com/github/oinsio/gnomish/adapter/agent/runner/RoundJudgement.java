package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory;
import com.github.oinsio.gnomish.adapter.agent.program.AnswerOutcome;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramEnd;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramFailures;
import com.github.oinsio.gnomish.app.port.agent.AnswerFileRead;
import com.github.oinsio.gnomish.app.port.agent.AnswerFileReading;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The judgement of one agent round that ended (FR1, FR2, FR8, UX2 of extract-agent-round-runner): its
 * usage and status from the dialect, and for a completed round its answer, read back through the one
 * answer read ({@link AnswerFileRead#onHost}, {@link AnswerFileRead#in}) — from the round scratch
 * directory when the dialect extracted it from the stream, from the round's environment when the agent
 * wrote it itself — and made the round's result by the one mapping the recovery of a snapshot shares
 * ({@link AnswerOutcome#ofAgent}; FR21, NFR-R5, design D15).
 *
 * <p>The dialect's status maps to the contract's: {@link RoundStatus.Completed} to {@code completed};
 * {@link RoundStatus.LimitReached} to {@code failed/limit} whose reason is exactly the bound, so the
 * engine's stop and its escalation report read {@code budget exhausted: <bound>}; {@link
 * RoundStatus.Broken} to {@code failed/infrastructure} naming the exit code, the dialect's reason and
 * the stderr tail. A round's usage rides every result the dialect judged; a usage that reports no wall
 * time takes the one the factory measured.
 *
 * <p>An agent round keeps no state past its end (FR9, NFR-R2, design D4 as amended 2026-11-21): a
 * completed round's {@code decision-request} answer leaves with its question and options and no
 * continuation key, a key the agent wrote removed, since the CLI's session lives on the worker and is
 * no record. A file that does not read as an answer is refused by the read, never passed on; and a
 * final answer the dialect found malformed — its CLI's structured-output retries exhausted among the
 * causes — is refused alike. What a refusal means follows the request's role, by the one mapping
 * (FR20, design D15 part 3): a {@code transform} round's quality failure, which burns one attempt and
 * starts no second request; a {@code verdict} or {@code decision} request's cannot-verify.
 *
 * <p>Implements FR1, FR2, FR8, FR9, FR20, FR21, NFR-R2, UX2 of extract-agent-round-runner.
 *
 * @param <E> the dialect's own event type
 */
final class RoundJudgement<E> {

    private final String name;
    private final AgentDialect<E> dialect;
    private final TaskExecutionEnvironment environment;
    private final RoundScratchDirectory scratch;
    private final ExecutorRequest request;

    /**
     * @param name the executor's name; never blank
     * @param dialect the round's dialect; never null
     * @param environment where the round ran; never null
     * @param scratch the round's scratch directory, holding its stderr; never null
     * @param request the run the round served; never null
     */
    RoundJudgement(
            String name,
            AgentDialect<E> dialect,
            TaskExecutionEnvironment environment,
            RoundScratchDirectory scratch,
            ExecutorRequest request) {
        this.name = name;
        this.dialect = dialect;
        this.environment = environment;
        this.scratch = scratch;
        this.request = request;
    }

    /** The round's {@code failed/infrastructure} for {@code cause}, quoting its stderr tail. */
    ExecutorResult failure(String cause) {
        return ProgramFailures.infrastructure(ProgramFailures.Subject.AGENT, name, cause, scratch.tailForFailure());
    }

    /**
     * Judges a round whose process exited and whose stdout reader was joined.
     *
     * @param exited the exit; never null
     * @param lines the round's line sink, joined; never null
     * @param answerFile the answer path the round's environment was handed; never null
     * @param roundEnd the instant an unfinished tool call is measured to; never null
     * @return the round's result; never null
     */
    ExecutorResult exited(ProgramEnd.Exited exited, DialectLines<E> lines, String answerFile, Instant roundEnd) {
        lines.reportCaps(name);
        ParsedEvents<E> events = lines.events();
        Usage usage = measured(dialect.usage(events, roundEnd), exited.wallTime());
        return switch (dialect.status(exited.exitCode(), events)) {
            case RoundStatus.Completed ignored -> answered(events, answerFile, usage);
            case RoundStatus.LimitReached limit ->
                new ExecutorResult(
                        Status.FAILED,
                        FailureClass.LIMIT,
                        null,
                        UntrustedText.agent(limit.bound()),
                        null,
                        usage,
                        List.of());
            case RoundStatus.Broken broken ->
                failure("exited " + exited.exitCode() + ": " + broken.reason().excerpt(512) + lines.volume())
                        .withUsage(usage);
        };
    }

    private ExecutorResult answered(ParsedEvents<E> events, String answerFile, Usage usage) {
        Path extracted = scratch.answerPath();
        AnswerFileReading read =
                switch (dialect.answer(request, events, extracted)) {
                    case DialectAnswer.Written ignored -> AnswerFileRead.onHost(extracted, UntrustedText::agent);
                    case DialectAnswer.NotWritten ignored ->
                        AnswerFileRead.in(environment, answerFile, UntrustedText::agent);
                    // No file was written: the dialect's reason is the whole cause, and nothing is quoted.
                    case DialectAnswer.Malformed malformed ->
                        new AnswerFileReading.Refused(
                                "its final answer does not match the answer's schema: "
                                        + malformed.reason().excerpt(512),
                                UntrustedText.agent(""));
                };
        return AnswerOutcome.ofAgent(request.role(), name, read, scratch.tailForFailure(), usage);
    }

    private static Usage measured(Usage usage, Duration wallTime) {
        return usage.wallMillis() != null
                ? usage
                : new Usage(usage.provenance(), wallTime.toMillis(), usage.models(), usage.tools());
    }
}
