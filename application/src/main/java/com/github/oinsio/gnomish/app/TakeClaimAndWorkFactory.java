package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.ClaimBeat;
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds a ready-to-use {@link TakeClaimAndWork} from the ingredients every take entry point already
 * holds, wiring the host and container resume runners in one place. The routing table above them is
 * assembled per resume by {@link TakeWorkRouter}, once the run's execution mode is known (design D8
 * of add-serve-sandbox-lifecycle). Extracted so that identical wiring is not triplicated across {@link
 * TakeBareAuto}, {@link TakeDisposition}, and {@code app.serve.TakeSlotRunner} — the sole crossing
 * point through which a caller (including one outside this package, task 4.3 of add-factory-serve)
 * gets a working instance without the three package-private resume-machinery classes being widened.
 *
 * <p>Implements FR9, FR10, D3 of add-tracker-port. Implements FR1, M2 of add-factory-serve.
 */
public final class TakeClaimAndWorkFactory {

    private TakeClaimAndWorkFactory() {}

    /**
     * Wires the resume chain and returns a {@link TakeClaimAndWork} bound to it (see class javadoc).
     *
     * <p>Implements FR1, M2 of add-factory-serve; NFR-O1 of harden-task-branch-contract.
     *
     * @param epochs this instance's tenure record, so the routing point's repair line (NFR-O1) can
     *     name the claim epoch it runs under; an empty book where none is recorded
     */
    public static TakeClaimAndWork forSlot(
            RunAssembly assembly,
            TaskGit git,
            Path worktreesRoot,
            String taskIdMdcKey,
            AbortHandler abortHandler,
            int abortThreshold,
            List<String> credentialEnvVarsToScrub,
            ClaimBeat heartbeat,
            ClaimLossFlag claimLossFlag,
            ContainerTakeSupport containerTakeSupport,
            ClaimEpochBook epochs) {
        var resumeRunner = new TakeResumeRunner(
                assembly,
                git,
                worktreesRoot,
                taskIdMdcKey,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                claimLossFlag);
        var containerResumeRunner = new TakeContainerResumeRunner(
                assembly,
                git,
                containerTakeSupport,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                claimLossFlag,
                taskIdMdcKey);
        return new TakeClaimAndWork(
                assembly,
                git,
                worktreesRoot,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                resumeRunner,
                heartbeat,
                claimLossFlag,
                containerTakeSupport,
                containerResumeRunner,
                epochs);
    }
}
