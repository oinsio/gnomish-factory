package com.github.oinsio.gnomish.executorcontract;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * What judging a law's settings against the schema an executor's describe declared produced
 * (FR9 of define-executor-contract): accepted, rejected with every violation, or a schema the
 * factory cannot use — one that names a schema to fetch, a meta-schema it does not know, or a
 * keyword value the validator cannot compile. A consumer maps each outcome to its own report; the
 * judgement is the only one, so the describe preflight and the conformance kit read a schema alike.
 *
 * <p>Implements FR9, FR12, UX1 of define-executor-contract.
 */
public sealed interface SettingsJudgement {

    /** The settings satisfy the schema. */
    record Accepted() implements SettingsJudgement {}

    /**
     * The settings break the schema.
     *
     * @param violations every violation, in the validator's evaluation order; never empty
     */
    record Rejected(List<SettingsViolation> violations) implements SettingsJudgement {

        public Rejected {
            violations = List.copyOf(violations);
        }
    }

    /**
     * The schema cannot be used to judge anything.
     *
     * @param reason what is wrong with it, in the validator's words, which quote the schema the
     *     program wrote; never null
     */
    record Unusable(UntrustedText reason) implements SettingsJudgement {}
}
