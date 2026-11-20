package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.domain.engine.port.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.executorcontract.SarifLevel;
import com.github.oinsio.gnomish.executorcontract.SarifResult;
import com.github.oinsio.gnomish.executorcontract.SarifRun;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pin-check of one run (FR16, design D10 of add-sandbox-core; design D8, D17 of
 * migrate-verify-to-executor-contract): the check's definition files must be byte-identical to the
 * law commit, compared as bare git objects in the factory clone ({@link GitObjects}) at the attempt
 * commit the check's working copy carries ({@link AttemptCommitWorkspace}). It wraps the executor
 * bound to a check in a {@link PinCheckedExecutor} when the check's pin set is not empty, so the
 * executor is reached only after the pin passed.
 *
 * <p>The pin set ({@link #pinSet}) is the union of the law's {@link VerifyCheck.Executor#pinPaths()}
 * and, when the executor's describe declares {@link Describe#checkIdIsPinnedPath()}, the check's
 * {@code checkId} — a repository-relative path. The describe flag is the only per-executor input; an
 * empty set passes vacuously and the bound executor is returned unwrapped.
 *
 * <p>Outcomes ({@link #refusal}): any difference — changed bytes, a path added on the gnome branch, or
 * a path removed from it — is a {@code completed} verdict answer of one {@code error} result per
 * differing path (an {@code error} result fails the check under every threshold, design D5). A law
 * pinning more than {@link Answer.Verdict#MAX_RESULTS} files that all differ exceeds what one answer
 * may carry: the answer's own refusal throws, and the verify chain resolves the check as
 * cannot-verify — fail-closed, never a pass. A pin that cannot be evaluated — a working copy
 * carrying no attempt commit, a law bound to no commit, a pinned blob over {@link
 * #PIN_READ_CAP_BYTES} — is {@code failed/infrastructure}: fail-closed, and the executor is not
 * reached either. The comparison is against the law commit, not the previous round, so a
 * substitution made at any earlier stage is caught at the point of use.
 *
 * <p>Holds immutable collaborators only; one instance serves every check of the run.
 *
 * <p>Implements FR16 of add-sandbox-core; FR6, FR16 of migrate-verify-to-executor-contract.
 */
public final class PinCheck {

    /**
     * The read cap for one pinned definition file on either side of the comparison: workflow files
     * and analyzer configs are kilobytes, so a megabyte bounds hostile volume (NFR-C1 of
     * add-sandbox-core) without ever refusing a legitimate pin.
     */
    static final long PIN_READ_CAP_BYTES = 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(PinCheck.class);

    private final GitObjects gitObjects;
    private final @Nullable ObjectId lawCommit;

    /**
     * @param gitObjects the factory clone's bare-object reader the comparison runs over; never null
     * @param lawCommit the peeled commit the run's law was frozen from (design D12 of
     *     add-base-ref-resolution) — compared as is, never re-resolved, so law and pin name one SHA by
     *     construction; {@code null} when the run's repository resolves no commit, which fails every
     *     pinned check closed
     */
    public PinCheck(GitObjects gitObjects, @Nullable ObjectId lawCommit) {
        this.gitObjects = gitObjects;
        this.lawCommit = lawCommit;
    }

    /**
     * The pin set of {@code check} run by an executor that described itself as {@code describe}.
     *
     * @param check the check; never null
     * @param describe the describe answer of the check's executor; never null
     * @return the law's pin paths, plus the check id when the describe declares it a pinned path,
     *     sorted and without duplicates; never null, possibly empty
     */
    public static SortedSet<String> pinSet(VerifyCheck.Executor check, Describe describe) {
        SortedSet<String> pins = new TreeSet<>(check.pinPaths());
        if (describe.checkIdIsPinnedPath()) {
            pins.add(check.checkId());
        }
        return pins;
    }

    /**
     * {@code bound} behind the pin-check of {@code check}, or {@code bound} itself when its pin set is
     * empty.
     *
     * @param bound the executor bound to {@code check} and {@code workspace}; never null
     * @param check the check the executor judges; never null
     * @param describe the describe answer of the check's executor; never null
     * @param workspace the check's working copy; never null
     * @return the executor the check round runs; never null
     */
    public Executor guarding(Executor bound, VerifyCheck.Executor check, Describe describe, Workspace workspace) {
        SortedSet<String> pins = pinSet(check, describe);
        return pins.isEmpty() ? bound : new PinCheckedExecutor(bound, this, check.checkId(), pins, workspace);
    }

    /**
     * The result that stands for the executor when {@code pins} do not all equal the law commit's
     * files at {@code workspace}'s attempt commit, or {@code null} when they do.
     *
     * @param checkId the check's id, named in the log and in a degradation's details; never null
     * @param pins the check's pin set; never empty
     * @param workspace the check's working copy; never null
     * @return the refusal, or {@code null} when the pin passes
     */
    @Nullable
    ExecutorResult refusal(String checkId, SortedSet<String> pins, Workspace workspace) {
        if (!(workspace instanceof AttemptCommitWorkspace attemptWorkspace)) {
            return unverifiable(
                    "pin-check requires the attempt-commit workspace",
                    "check '" + checkId + "' declares pin paths " + pins + " but the workspace is "
                            + workspace.getClass().getName() + ", which carries no attempt commit to compare against");
        }
        if (lawCommit == null) {
            return unverifiable(
                    "pin-check has no law commit to compare against",
                    "the run's law is bound to no commit — its repository root resolves no checkout — so"
                            + " pinned definition files " + pins + " cannot be compared");
        }
        ObjectId attempt = ObjectId.of(attemptWorkspace.attemptCommitSha());
        List<SarifResult> diffs = new ArrayList<>();
        for (String path : pins) {
            PinnedBlob base = PinnedBlob.read(gitObjects, lawCommit, path, PIN_READ_CAP_BYTES);
            PinnedBlob ours = PinnedBlob.read(gitObjects, attempt, path, PIN_READ_CAP_BYTES);
            if (base.tooLarge() || ours.tooLarge()) {
                return unverifiable(
                        "pin-check cannot compare an oversized pinned file",
                        "pinned definition file '" + path + "' exceeds " + PIN_READ_CAP_BYTES
                                + " bytes and cannot be byte-compared");
            }
            String how = base.divergence(ours);
            if (how != null) {
                diffs.add(new SarifResult(
                        null,
                        SarifLevel.ERROR,
                        UntrustedText.manifest("pinned definition file '" + path + "' " + how),
                        UntrustedText.manifest(path),
                        null));
            }
        }
        if (diffs.isEmpty()) {
            return null;
        }
        log.warn(
                OperatorEvent.EXTERNAL_CHECK_PIN_MISMATCH.head()
                        + "pin-check failed for check '{}': {} pinned definition file(s) differ from '{}';"
                        + " the executor is not invoked",
                checkId,
                diffs.size(),
                lawCommit.hex());
        var verdict = new Answer.Verdict(List.of(new SarifRun(UntrustedText.factory("pin-check"), diffs, null)));
        return new ExecutorResult(
                Status.COMPLETED,
                null,
                null,
                null,
                ExecutorContractJson.answerTree(verdict),
                Usage.knownZero(),
                List.of());
    }

    /** A pin that cannot be evaluated: {@code failed/infrastructure}, the executor not reached. */
    private static ExecutorResult unverifiable(String reason, String details) {
        return new ExecutorResult(
                Status.FAILED,
                FailureClass.INFRASTRUCTURE,
                null,
                UntrustedText.factory(reason),
                null,
                Usage.knownZero(),
                List.of(),
                null,
                null,
                // MANIFEST: the sentence interpolates the check id and the law's pin paths raw.
                UntrustedText.manifest(details));
    }
}
