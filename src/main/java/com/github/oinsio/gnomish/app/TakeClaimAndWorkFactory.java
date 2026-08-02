package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.ClaimBeat;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds a ready-to-use {@link TakeClaimAndWork} from the ingredients every take entry point already
 * holds, wiring the {@link TakeResumeRunner}/{@link TakeDispositionResume}/{@link TakeDecisionResume}
 * resume chain in one place. Extracted so that identical wiring is not triplicated across {@link
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
     * <p>Implements FR1, M2 of add-factory-serve.
     */
    public static TakeClaimAndWork forSlot(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            String taskIdMdcKey,
            AbortHandler abortHandler,
            int abortThreshold,
            List<String> credentialEnvVarsToScrub,
            ClaimBeat heartbeat,
            ClaimLossFlag claimLossFlag) {
        var resumeRunner = new TakeResumeRunner(
                assembly,
                worktreesRoot,
                taskIdMdcKey,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                claimLossFlag);
        var dispositionResume =
                new TakeDispositionResume(resumeRunner, new TakeDecisionResume(resumeRunner), worktreesRoot);
        return new TakeClaimAndWork(
                assembly,
                worktreesRoot,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                dispositionResume,
                heartbeat,
                claimLossFlag);
    }
}
