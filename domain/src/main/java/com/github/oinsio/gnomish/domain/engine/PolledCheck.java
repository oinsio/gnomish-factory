package com.github.oinsio.gnomish.domain.engine;

import java.util.Map;

/**
 * What {@link PendingPoll} hands the verify chain for one executor check: the verdict answer the
 * check's rounds collapse to, which {@link VerdictDerivation} judges, and the {@link CheckOutcome}
 * the chain carries on the check's finish event (NFR-O1, UX3 of migrate-verify-to-executor-contract).
 *
 * @param answer the verdict answer; never null
 * @param outcome what the check's rounds came to; never null
 * @param byModel the usage the last round reported, keyed by resolved model id; empty when none
 *     reported it — a judge check's participant entry (FR4 of extract-agent-round-runner)
 */
record PolledCheck(VerdictAnswer answer, CheckOutcome outcome, Map<String, ModelUsage> byModel) {

    PolledCheck {
        byModel = Map.copyOf(byModel);
    }
}
