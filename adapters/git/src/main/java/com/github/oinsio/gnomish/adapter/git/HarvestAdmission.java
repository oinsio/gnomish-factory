package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admission gate at the harvest (design D18 of tighten-spec-contract): the one way gnome content
 * from a box enters the task branch. A harvest only fetches the box's branch into the task's
 * quarantine ({@link FactoryRegister#quarantine}); this class reads what landed there, requires it
 * to descend from the current branch tip, judges {@code register..top} with the one {@link
 * GnomeRangeInspection}, and only then moves the branch to it — with the register, in one ref
 * transaction, when the top is the factory's own in-box closing commit ({@link
 * AdmissionRule#closing()}), the branch alone mid-round. A refused or unverifiable range moves
 * nothing, so it never becomes durable: git's own receive quarantine, where a failed pre-receive
 * check updates no ref.
 *
 * <p>Callers: the mid-round harvest ({@link MidRoundHarvestListener}, which then pushes what was
 * admitted through {@link #pushAdmitted}), the snapshot ({@link EnvironmentRoundSnapshot}), the
 * state harvest ({@link EnvironmentAttemptPersistence}) and the salvage ({@link EnvironmentSalvage}),
 * whose closing pushes carry branch and register to origin together. A range already on the branch
 * is admitted again without a move — a re-harvest of an admitted range, or the persist's re-judgment
 * of an admitted snapshot — so running an admission twice equals running it once.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract; FR5, FR21, FR23 of add-sandbox-core.
 */
final class HarvestAdmission {

    private static final Logger log = LoggerFactory.getLogger(HarvestAdmission.class);

    private final GitRunner runner;
    private final Path cloneDir;
    private final FactoryRegister register;
    private final GnomeRangeInspection inspection;

    /**
     * @param runner the git subprocess seam
     * @param cloneDir the factory clone the harvest lands in
     */
    HarvestAdmission(GitRunner runner, Path cloneDir) {
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.register = new FactoryRegister(runner);
        this.inspection = new GnomeRangeInspection(runner, cloneDir);
    }

    /**
     * What the last harvest of {@code taskId} landed in its quarantine, verified before use: a
     * harvest that just succeeded left a commit there, so a quarantine that does not resolve fails
     * the caller rather than being admitted as nothing.
     *
     * @throws com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException if it does not
     *     resolve
     */
    String quarantined(String taskId) {
        String quarantine = FactoryRegister.quarantine(taskId).name();
        return VerifiedTip.required(
                quarantine, "rev-parse", runner.run(cloneDir, "rev-parse", "--verify", "--quiet", quarantine));
    }

    /**
     * Admits {@code top} under {@code rule}: judged against the register, moved onto the branch
     * when clean.
     *
     * @param taskId the tracker's original taskId; names the branch and the register
     * @param top the commit to admit, an object in the clone
     * @param rule what the range may contain
     * @return the branch tip carrying it, or why the branch did not move
     */
    AdmittedRange admit(String taskId, String top, AdmissionRule rule) {
        GitCommandResult registerRead = read(FactoryRegister.of(taskId).name());
        GitCommandResult branchRead = read(FactoryRegister.branchRef(taskId));
        Optional<String> registered = VerifiedTip.read(registerRead);
        Optional<String> branchTip = VerifiedTip.read(branchRead);
        if (registered.isEmpty() || branchTip.isEmpty()) {
            GitCommandResult failed = registered.isEmpty() ? registerRead : branchRead;
            return new AdmittedRange.Unverified(UntrustedText.factory("the task branch or its register of task "
                    + taskId + " does not resolve in " + cloneDir + ": " + VerifiedTip.failureReason(failed)));
        }
        String tip = branchTip.get();
        if (runner.run(cloneDir, "merge-base", "--is-ancestor", top, tip).succeeded()) {
            return new AdmittedRange.Admitted(tip);
        }
        GitCommandResult descends = runner.run(cloneDir, "merge-base", "--is-ancestor", tip, top);
        if (descends.termination() == Termination.EXITED && descends.exitCode() == 1) {
            return new AdmittedRange.Refused(UntrustedText.factory("the harvested " + top
                    + " does not descend from the branch tip " + tip + " (history rewrite); it"
                    + " is held at " + FactoryRegister.quarantine(taskId).name() + " in " + cloneDir
                    + " until the next harvest"));
        }
        if (!descends.succeeded()) {
            return new AdmittedRange.Unverified(descends.cannotVerifyDetail());
        }
        return switch (inspection.inspect(registered.get(), top, rule)) {
            case RangeVerdict.Violated violated -> new AdmittedRange.Refused(violated.reason());
            case RangeVerdict.CannotVerify unverified -> new AdmittedRange.Unverified(unverified.evidence());
            case RangeVerdict.Clean ignored -> move(taskId, top, tip, rule);
        };
    }

    /**
     * The mid-round push of what was admitted: the task branch alone, best-effort — never the
     * register, which names the last closing commit. With no {@code origin} configured this is a
     * silent no-op; a failed push is one WARN and returns normally — never a throw, a retry or a
     * force — because the admission already made the work durable in the clone.
     */
    void pushAdmitted(String taskId) {
        if (!new OriginRemote(runner).isConfigured(cloneDir)) {
            return;
        }
        String branch = TaskIdSanitizer.branchName(taskId);
        GitCommandResult result = new RefspecPush(runner).push(cloneDir, branch);
        String outcome = PushOutcome.describe("mid-round push", result);
        if (outcome != null) {
            log.warn(
                    OperatorEvent.BRANCH_PUSH_FAILED.head() + "{}: branch={}, stderr={}",
                    outcome,
                    branch,
                    result.stderr().forLog());
        }
    }

    private AdmittedRange move(String taskId, String top, String expectedTip, AdmissionRule rule) {
        GitCommandResult moved = rule.closing()
                ? register.close(cloneDir, taskId, top, expectedTip)
                : register.advance(cloneDir, taskId, top, expectedTip);
        if (!moved.succeeded()) {
            return new AdmittedRange.Unverified(moved.failureDetail("admission's ref transaction"));
        }
        return new AdmittedRange.Admitted(top);
    }

    private GitCommandResult read(String ref) {
        return GitReadGate.answered(ref, "rev-parse", runner.run(cloneDir, "rev-parse", "--verify", "--quiet", ref));
    }
}
