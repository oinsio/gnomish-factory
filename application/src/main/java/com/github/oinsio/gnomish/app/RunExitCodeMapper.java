package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.DivergedBranchException;
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
 *   <tr><td>none</td><td>4</td><td>retired: stdin ending inside an interactive adapter, removed
 *       with the adapters (FR5 of remove-interactive-console); kept as a gap so no other code
 *       shifts</td></tr>
 *   <tr><td>{@link com.github.oinsio.gnomish.app.port.git.DivergedBranchException}</td><td>5</td>
 *       <td>local/origin branch divergence on a claimless run, which FR8's automatic discard
 *       deliberately does not cover</td></tr>
 *   <tr><td>{@link TaskNotFoundException}</td><td>6</td><td>{@code status}/{@code usage}: no task
 *       branch found (FR13, UX3) — a normal outcome, not a crash</td></tr>
 *   <tr><td>{@link BranchShapeRefusedException}</td><td>7</td><td>{@code status}: the branch
 *       classifies as a quarantine shape and refuses inspection (FR16)</td></tr>
 *   <tr><td>{@link RunParkedException}, {@code Escalated}</td><td>10</td><td>the run stopped at an
 *       escalation, parked on its branch (FR1, FR7 of make-run-headless)</td></tr>
 *   <tr><td>{@link DecisionRequiredException}</td><td>10</td><td>a resume without {@code --decision}
 *       met a {@code DecisionNeeded} report; the question was restated and the task stays parked
 *       (FR4 of make-run-headless)</td></tr>
 *   <tr><td>{@link RunParkedException}, {@code Paused}</td><td>11</td><td>the run stopped at a
 *       manual checkpoint, parked on its branch (FR2, FR7 of make-run-headless)</td></tr>
 *   <tr><td>{@link AbortedException}</td><td>12</td><td>persistence failed</td></tr>
 *   <tr><td>{@link InternalErrorException}</td><td>1</td><td>unreachable-in-process internal error</td></tr>
 *   <tr><td>anything else</td><td>1</td><td>generic internal-error fallback</td></tr>
 * </table>
 *
 * <p>Implements FR9, FR12, FR13, UX3, D10 of add-git-workflow, add-manual-run; FR8, FR16 of
 * harden-task-branch-contract; FR5, FR8 of remove-interactive-console; FR4, FR7 of
 * make-run-headless.
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
            case DivergedBranchException ignored -> 5;
            case TaskNotFoundException ignored -> 6;
            case BranchShapeRefusedException ignored -> 7;
            case RunParkedException parked -> parked.checkpoint() ? 11 : 10;
            case DecisionRequiredException ignored -> 10;
            case AbortedException ignored -> 12;
            case InternalErrorException ignored -> 1;
            default -> 1;
        };
    }
}
