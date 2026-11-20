package com.github.oinsio.gnomish.app.conformance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.executorcontract.Capability;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.Status;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The conformance kit's core (FR12, FR24 of define-executor-contract; design D2, D18, D19): every
 * case of {@link KitCase}, driven against a {@link KitSubject} through the mirror interface alone, so
 * a program and a built-in run through the same cases. The describe and settings-schema cases judge
 * what describing the candidate yielded ({@link DescribeCases}); every other case opens a run of its
 * own and starts one request — in its role, in a role the describe does not list, or in the first
 * role it lists — judged by {@link CaseJudgement}, the two pending cases continuing their run by poll
 * ({@link PendingCases}). A role the describe does not list, a pending case of a candidate without
 * {@code poll}, and a wire-only case of a subject off the wire are not
 * applicable; when the factory refused the describe answer, no run reaches the candidate. An
 * executor that throws from {@code start} or {@code poll} fails the case: the contract reports every
 * outcome as a result.
 *
 * <p>The pending cases start under the operator's pending settings. Every other request carries the
 * subject's ordinary settings — empty for a program; an in-process built-in's own, which its
 * settings-schema case judges in place of the pending settings a program's is judged against (design
 * D18 of migrate-verify-to-executor-contract).
 *
 * <p>Implements FR8, FR12, FR23, FR24, M4 of define-executor-contract; FR1, FR11, FR12, FR13 of
 * migrate-verify-to-executor-contract.
 */
@NullMarked
public final class ConformanceKit {

    private static final List<Role> LIVE_ROLES = List.of(Role.TRANSFORM, Role.VERDICT, Role.DECISION);

    private static final List<Role> UNDECLARED_PREFERENCE =
            List.of(Role.EFFECT, Role.TRANSFORM, Role.VERDICT, Role.DECISION);

    private final Map<String, Object> pendingSettings;
    private final PendingCases pending;

    /**
     * @param pendingSettings the settings under which the candidate goes pending, the command's
     *     {@code --pending-settings}; never null, possibly empty
     * @param sleeper the wait between two polls; never null
     */
    public ConformanceKit(Map<String, ?> pendingSettings, Sleeper sleeper) {
        this.pendingSettings = Map.<String, Object>copyOf(pendingSettings);
        this.pending = new PendingCases(sleeper);
    }

    /**
     * Runs every case against {@code subject}.
     *
     * @param subject the candidate; never null
     * @return the verdict of every case; never null
     */
    public KitReport run(KitSubject subject) {
        Map<KitCase, CaseVerdict> verdicts = new EnumMap<>(KitCase.class);
        KitSubject.Described described = subject.describe();
        CaseVerdict settings =
                DescribeCases.settings(described, subject.onWire() ? pendingSettings : subject.settings());
        verdicts.put(KitCase.DESCRIBE, DescribeCases.describe(described, settings));
        verdicts.put(KitCase.SETTINGS_SCHEMA, settings);
        Describe answer = described.answer();
        for (KitCase kase : KitCase.values()) {
            if (!verdicts.containsKey(kase)) {
                verdicts.put(kase, reached(subject, answer, settings, kase));
            }
        }
        return new KitReport(verdicts);
    }

    /**
     * The verdict of a case that needs a run, unless no run reaches the candidate: the factory refused
     * the describe answer, or the settings schema refuses the settings — the refusal the preflight
     * makes of a program, made here of a subject the preflight does not describe (a built-in), so both
     * subjects get the same verdict per case.
     */
    private CaseVerdict reached(KitSubject subject, @Nullable Describe answer, CaseVerdict settings, KitCase kase) {
        if (answer == null) {
            return new CaseVerdict.NotApplicable(
                    "the factory refused the describe answer, so no run reaches the candidate");
        }
        if (settings instanceof CaseVerdict.Failed) {
            return new CaseVerdict.NotApplicable(
                    "the settings schema refuses the settings the kit sends, so no run reaches the candidate");
        }
        return run(subject, answer, kase);
    }

    private CaseVerdict run(KitSubject subject, Describe answer, KitCase kase) {
        if (kase.wireOnly() && !subject.onWire()) {
            return new CaseVerdict.NotApplicable("the case exists only on the wire, and this executor runs in process");
        }
        if (kase.pending() && !answer.capabilities().contains(Capability.POLL)) {
            return new CaseVerdict.NotApplicable("the describe declares no poll capability");
        }
        Role role = role(kase, answer);
        if (role == null) {
            return new CaseVerdict.NotApplicable(
                    kase == KitCase.UNKNOWN_ROLE
                            ? "the describe lists every role"
                            : "the describe lists no role "
                                    + (kase.role() == null
                                            ? "transform, verdict or decision"
                                            : kase.role().wire()));
        }
        try (KitSubject.CaseRun run = subject.open(kase, answer)) {
            ObjectNode settings = kase.pending()
                    ? ExecutorContractJson.settingsTree(pendingSettings)
                    : ExecutorContractJson.settingsTree(subject.settings());
            ExecutorRequest request = CaseJudgement.request(role, run.workspace(), settings);
            ExecutorResult result;
            try {
                result = run.executor().start(request);
            } catch (RuntimeException e) {
                return new CaseVerdict.Failed(CaseJudgement.threw("Executor.start", e));
            }
            if (kase == KitCase.UNKNOWN_ROLE) {
                return result.status() == Status.COMPLETED
                        ? new CaseVerdict.Failed(
                                "answered completed in role " + role.wire() + ", which its describe does not list")
                        : new CaseVerdict.Passed();
            }
            if (kase.pending()) {
                return pending.judge(kase, run, request, result);
            }
            return CaseJudgement.judge(request, result, run.violations());
        }
    }

    private static @Nullable Role role(KitCase kase, Describe answer) {
        List<Role> listed = answer.roles();
        Role bound = kase.role();
        if (bound != null) {
            return listed.contains(bound) ? bound : null;
        }
        List<Role> candidates = kase == KitCase.UNKNOWN_ROLE ? UNDECLARED_PREFERENCE : LIVE_ROLES;
        boolean wantListed = kase != KitCase.UNKNOWN_ROLE;
        return candidates.stream()
                .filter(r -> listed.contains(r) == wantListed)
                .findFirst()
                .orElse(null);
    }
}
