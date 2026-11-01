package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.RoundExecutor;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;

/**
 * The one spelling of a round's outcome on the operator's planes (NFR-O2, UX3 of
 * define-executor-contract): the round's log line and its ledger {@code roundOutcome} line both
 * name the executor, role and status in these words, which are the executor contract's own wire
 * tokens — the engine's status read back into the contract's vocabulary, never a third one.
 *
 * <p>The role is the use site's: a stage round is a {@code transform} (the law has no role field;
 * the stage implies it). The protocol version is named only for an executor that answered describe,
 * and is the one version the factory speaks, since the describe preflight refuses any other. The
 * bound is the reason a {@code failed/limit} round reported — the bound it reached.
 *
 * <p>The mapping covers both ends of the declared pair {@code ExecutionResult} ↔ the contract's
 * {@link Status} and {@link FailureClass} by exhaustive switches; {@code RoundOutcomeWordsSpec}
 * asserts every engine constant reads back to its namesake contract constant.
 *
 * <p>Implements NFR-O2, UX3 of define-executor-contract.
 */
public final class RoundOutcomeWords {

    /** The role of every stage round: the stage use site implies {@code transform} (FR1, D1). */
    public static final String STAGE_ROLE = Role.TRANSFORM.wire();

    private RoundOutcomeWords() {}

    /** The contract status token of {@code status}: {@code completed}, {@code failed} or {@code pending}. */
    public static String status(ExecutionResult.Status status) {
        return switch (status) {
            case ExecutionResult.Completed ignored -> Status.COMPLETED.wire();
            case ExecutionResult.Failed ignored -> Status.FAILED.wire();
            case ExecutionResult.Pending ignored -> Status.PENDING.wire();
        };
    }

    /** The contract failure-class token of a failed {@code status}; null for any other status. */
    public static @Nullable String failureClass(ExecutionResult.Status status) {
        if (!(status instanceof ExecutionResult.Failed failed)) {
            return null;
        }
        return switch (failed.failureClass()) {
            case QUALITY -> FailureClass.QUALITY.wire();
            case INFRASTRUCTURE -> FailureClass.INFRASTRUCTURE.wire();
            case LIMIT -> FailureClass.LIMIT.wire();
        };
    }

    /** The ledger's {@code <status>[/<class>]}: {@code completed}, {@code failed/limit}, ... (UX3). */
    public static String statusWithClass(ExecutionResult.Status status) {
        String failureClass = failureClass(status);
        return failureClass == null ? status(status) : status(status) + "/" + failureClass;
    }

    /** The bound a {@code failed/limit} round reached, as it reported it; null for any other status. */
    public static @Nullable UntrustedText bound(ExecutionResult.Status status) {
        return status instanceof ExecutionResult.Failed failed
                        && failed.failureClass() == ExecutionResult.FailureClass.LIMIT
                ? failed.reason()
                : null;
    }

    /** The protocol version the round was spoken in; null for an executor that answered no describe. */
    public static @Nullable Integer protocolVersion(RoundExecutor executor) {
        return executor.version() == null ? null : Describe.SPOKEN_PROTOCOL_VERSION;
    }

    /** The log plane's {@code <name>[@<version>]}, the version through its log exit (UX3). */
    public static String executorForLog(RoundExecutor executor) {
        UntrustedText version = executor.version();
        return version == null ? executor.name() : executor.name() + "@" + version.forLog();
    }
}
