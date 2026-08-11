package com.github.oinsio.gnomish.adapter.environment;

import com.github.oinsio.gnomish.domain.pipeline.Sandbox;
import java.util.List;

/**
 * Reconciles a stage's repo-declared sandbox {@code needs} against the bound
 * adapter's {@link CapabilityPassport}, fail-closed (design D8, FR14): the single
 * decision point the factory consults before starting a stage. A stage runs only
 * when every declared need is satisfied by the bound adapter's passport; any
 * unmet or unrecognized need refuses the stage, and the returned tokens name
 * exactly what was unmet so the operator sees one clear error rather than a
 * mid-task crash (UX2).
 *
 * <p>Reconciliation is one-directional — the repo may only tighten (FR14). It
 * never chooses or weakens a binding; the binding is resolved from operator
 * config by {@code BindingResolver}, and this component only checks the needs
 * against the passport that binding carries.
 *
 * <p>Implements FR14 of add-sandbox-core.
 */
public class SandboxReconciler {

    /**
     * The declared needs of {@code sandbox} that {@code passport} does not satisfy,
     * in declaration order — empty when the passport satisfies every need (the
     * stage may start). A need whose token is outside the closed {@link
     * SandboxNeed} vocabulary is always unmet: an unrecognized requirement cannot
     * be proven satisfied, so it is reported by its raw token for the operator to
     * fix.
     *
     * @param sandbox the stage's sandbox declarations; never null
     * @param passport the bound adapter's passport; never null
     * @return the unmet need tokens in declaration order; empty when all are met
     */
    public List<String> unmetNeeds(Sandbox sandbox, CapabilityPassport passport) {
        return sandbox.needs().stream()
                .filter(token -> isUnmet(token, passport))
                .toList();
    }

    private static boolean isUnmet(String token, CapabilityPassport passport) {
        return SandboxNeed.fromToken(token)
                .map(need -> !need.satisfiedBy(passport))
                .orElse(true);
    }
}
