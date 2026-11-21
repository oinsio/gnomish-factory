package com.github.oinsio.gnomish.adapter.agent.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramEnd;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramFailures;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultRead;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultRead.AnswerRead;
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
import org.jspecify.annotations.Nullable;

/**
 * The judgement of one agent round that ended (FR1, FR2, FR8, UX2 of extract-agent-round-runner): its
 * usage and status from the dialect, and for a completed round its answer, read back through the one
 * capped answer read ({@link ProgramResultRead#answerOnHost}, {@link ProgramResultRead#answerIn}) —
 * from the round scratch directory when the dialect extracted it from the stream, from the round's
 * environment when the agent wrote it itself.
 *
 * <p>The dialect's status maps to the contract's: {@link RoundStatus.Completed} to {@code completed};
 * {@link RoundStatus.LimitReached} to {@code failed/limit} whose reason is exactly the bound, so the
 * engine's stop and its escalation report read {@code budget exhausted: <bound>}; {@link
 * RoundStatus.Broken} to {@code failed/infrastructure} naming the exit code, the dialect's reason and
 * the stderr tail. A round's usage rides every result the dialect judged; a usage that reports no wall
 * time takes the one the factory measured.
 *
 * <p>Continuation (FR9, NFR-R2, design D4): a round started with a continuation whose resume the
 * dialect says the CLI refused is no result but a {@link RoundAttempt.ResumeRefused}, which the round
 * answers by a fresh start; a completed round's {@code decision-request} answer carries the dialect's
 * session as its continuation key ({@link SessionContinuation#stamped}).
 *
 * <p>Implements FR1, FR2, FR8, FR9, NFR-R2, UX2 of extract-agent-round-runner.
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
     * @return the round's result, or the CLI's refusal to resume the request's continuation; never null
     */
    RoundAttempt exited(ProgramEnd.Exited exited, DialectLines<E> lines, String answerFile, Instant roundEnd) {
        lines.reportCaps(name);
        ParsedEvents<E> events = lines.events();
        if (request.continuation() != null) {
            UntrustedText stderrTail = scratch.tailForFailure();
            if (dialect.resumeRefused(exited.exitCode(), events, stderrTail)) {
                return new RoundAttempt.ResumeRefused(stderrTail);
            }
        }
        return new RoundAttempt.Ended(judged(exited, events, answerFile, roundEnd));
    }

    private ExecutorResult judged(
            ProgramEnd.Exited exited, ParsedEvents<E> events, String answerFile, Instant roundEnd) {
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
                failure("exited " + exited.exitCode() + ": " + broken.reason().excerpt(512))
                        .withUsage(usage);
        };
    }

    private ExecutorResult answered(ParsedEvents<E> events, String answerFile, Usage usage) {
        Path extracted = scratch.answerPath();
        AnswerRead read =
                switch (dialect.answer(request, events, extracted)) {
                    case DialectAnswer.Written ignored ->
                        ProgramResultRead.answerOnHost(extracted, UntrustedText::agent);
                    case DialectAnswer.NotWritten ignored ->
                        ProgramResultRead.answerIn(environment, answerFile, UntrustedText::agent);
                    case DialectAnswer.Malformed malformed ->
                        new AnswerRead.Refused("its final answer does not match the answer's schema: "
                                + malformed.reason().excerpt(512));
                };
        return switch (read) {
            case AnswerRead.None ignored -> completed(null, usage);
            case AnswerRead.Read answer ->
                completed(SessionContinuation.stamped(answer.answer(), dialect.continuation(events)), usage);
            case AnswerRead.Refused refused -> failure(refused.cause()).withUsage(usage);
        };
    }

    private static ExecutorResult completed(@Nullable JsonNode answer, Usage usage) {
        return new ExecutorResult(Status.COMPLETED, null, null, null, answer, usage, List.of());
    }

    private static Usage measured(Usage usage, Duration wallTime) {
        return usage.wallMillis() != null
                ? usage
                : new Usage(usage.provenance(), wallTime.toMillis(), usage.models(), usage.tools());
    }
}
