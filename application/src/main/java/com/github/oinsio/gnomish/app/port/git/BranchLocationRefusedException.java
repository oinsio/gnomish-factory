package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;

/**
 * Thrown by a reader of the task branch when the lookup found the branch on origin and the fetch
 * refused it under object validation ({@link BranchLocation.Refused}), so the caller stops with the
 * refusal's report rather than reading a branch this clone cannot hold (FR5 of
 * own-git-transfer-argv).
 *
 * <p>Sibling of {@link BranchLocationUnavailableException}, which is the infrastructure failure of
 * the same lookup; this one is deterministic — origin answered, and the answer is a history git will
 * not accept — so no retry, no claim release and no recovery budget applies to it. The take's own
 * classification maps the refusal to a quarantine shape before any of these readers run, so what
 * reaches this exception is an inspection ({@code status}, {@code usage}), a manual resume, or a
 * reader inside a run whose branch was readable when it started.
 *
 * <p>Implements FR5 of own-git-transfer-argv.
 */
public final class BranchLocationRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The report is a paragraph the factory composed around git's validation refusal, so it arrives
     * as a carrier and leaves it here through the log exit {@link UntrustedText#forLog()} — the same
     * shape as {@link BranchLocationUnavailableException}: this message is rendered into a log
     * record and the abort diagnosis, where the gate cannot see inside an exception's text.
     *
     * @param taskId the task whose branch was refused; never blank
     * @param report what git refused, from {@link BranchLocation.Refused#report()}
     */
    public BranchLocationRefusedException(String taskId, UntrustedText report) {
        super("the task branch for " + taskId + " was found on origin but refused by object validation: "
                + report.forLog() + "; stopping instead of reading a branch this clone cannot hold");
    }
}
