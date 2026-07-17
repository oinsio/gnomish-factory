package com.github.oinsio.gnomish.app;

import org.springframework.boot.ExitCodeExceptionMapper;
import org.springframework.stereotype.Component;

/**
 * Maps every terminal {@code gnomish run} exception to its exit code (FR12, design D10)
 * via Spring Boot's {@link ExitCodeExceptionMapper}: {@link
 * ExitCodeExceptionMapper#getExitCode(Throwable)} returns a plain {@code int}, with no
 * sentinel "no opinion" value in its contract — Boot simply keeps the highest exit code
 * offered by any registered mapper for a given exception, and falls back to 1 for an
 * uncaught exception if no mapper claims it. This mapper claims every {@link Throwable}
 * explicitly, including a final fallback to 1 for anything unrecognized, so there is
 * never a case Boot's own default has to handle.
 *
 * <table>
 *   <caption>Exception to exit code</caption>
 *   <tr><th>Exception</th><th>Exit code</th><th>Meaning</th></tr>
 *   <tr><td>{@link UsageException}</td><td>2</td><td>usage error</td></tr>
 *   <tr><td>{@link PipelineLoadFailedException}</td><td>3</td><td>pipeline load failure</td></tr>
 *   <tr><td>{@link InputExhaustedException}</td><td>4</td><td>stdin exhausted mid-stage</td></tr>
 *   <tr><td>{@link EscalationEofException}</td><td>10</td><td>Ctrl-D at the escalation resume prompt</td></tr>
 *   <tr><td>{@link CheckpointEofException}</td><td>11</td><td>Ctrl-D at the manual checkpoint prompt</td></tr>
 *   <tr><td>{@link AbortedException}</td><td>12</td><td>persistence failed</td></tr>
 *   <tr><td>{@link InternalErrorException}</td><td>1</td><td>unreachable-in-process internal error</td></tr>
 *   <tr><td>anything else</td><td>1</td><td>generic internal-error fallback</td></tr>
 * </table>
 *
 * <p>Implements FR12, D10 of add-manual-run.
 */
@Component
public final class RunExitCodeMapper implements ExitCodeExceptionMapper {

    /**
     * @param exception the uncaught exception the runner terminated with; never null
     * @return the exit code for {@code exception}'s type, or 1 as the generic
     *     internal-error fallback for anything unrecognized
     */
    @Override
    public int getExitCode(Throwable exception) {
        return switch (exception) {
            case UsageException ignored -> 2;
            case PipelineLoadFailedException ignored -> 3;
            case InputExhaustedException ignored -> 4;
            case EscalationEofException ignored -> 10;
            case CheckpointEofException ignored -> 11;
            case AbortedException ignored -> 12;
            case InternalErrorException ignored -> 1;
            default -> 1;
        };
    }
}
