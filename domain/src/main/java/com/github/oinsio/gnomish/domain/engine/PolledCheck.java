package com.github.oinsio.gnomish.domain.engine;

/**
 * What {@link PendingPoll} hands the verify chain for one executor check: the verdict answer the
 * check's rounds collapse to, which {@link VerdictDerivation} judges, and the {@link CheckOutcome}
 * the chain carries on the check's finish event (NFR-O1, UX3 of migrate-verify-to-executor-contract).
 *
 * @param answer the verdict answer; never null
 * @param outcome what the check's rounds came to; never null
 */
record PolledCheck(VerdictAnswer answer, CheckOutcome outcome) {}
