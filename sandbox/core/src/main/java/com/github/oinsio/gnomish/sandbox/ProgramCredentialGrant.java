package com.github.oinsio.gnomish.sandbox;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The credential grant of a {@code program} executor's child (NFR-S1 of define-executor-contract,
 * design D10; ADR 0017): the child's <em>own resolved layer</em>, each granted name resolved through
 * the run's child-environment value source and added to that one exec's factory-set variables. It
 * is never a name declared on the {@link ChildEnvAllowlist}: that declared set is what every child
 * is denied, so {@code compose} and {@code resolve} strip it. A name granted here therefore reaches
 * the program's process and no sibling's — a {@code command} check composed from the same allowlist
 * names none of it.
 *
 * <p>Built by {@link ChildEnvAllowlist#programGrant} over the allowlist's own passthrough, declared
 * set and value source, so it can judge a name against both shared layers; like the allowlist it
 * exposes no accessor for the source.
 *
 * <p>Implements NFR-S1 of define-executor-contract.
 */
public final class ProgramCredentialGrant {

    private final Set<String> passthroughNames;
    private final Set<String> withheldNames;
    private final ChildEnvValues values;

    ProgramCredentialGrant(Collection<String> passthroughNames, Set<String> withheldNames, ChildEnvValues values) {
        this.passthroughNames = Set.copyOf(passthroughNames);
        this.withheldNames = Set.copyOf(withheldNames);
        this.values = values;
    }

    /**
     * Whether {@code name} can be granted to one child alone — what the describe preflight asks of
     * every name the law grants before the first round. A query: no value leaves.
     *
     * @param name the credential name; never null
     * @return its standing; never null
     */
    public CredentialGrantStanding standing(String name) {
        if (withheldNames.contains(name)) {
            return CredentialGrantStanding.WITHHELD;
        }
        if (passthroughNames.contains(name)) {
            return CredentialGrantStanding.PASSED_THROUGH;
        }
        return values.find(name).isPresent() ? CredentialGrantStanding.GRANTABLE : CredentialGrantStanding.NO_VALUE;
    }

    /**
     * The factory-set variables that carry {@code names} to the one {@code program} exec they were
     * granted to, each resolved live, in {@code names}' order.
     *
     * @param names the executor's granted names ({@code ExecutorCredentialNames#of}); never null
     * @return every name with its value; never null, unmodifiable
     * @throws IllegalArgumentException if a name is not {@link CredentialGrantStanding#GRANTABLE} —
     *     the preflight refuses each such name first, so reaching here means its value went away
     *     since, or the caller skipped the preflight
     */
    public Map<String, String> grant(Collection<String> names) {
        Map<String, String> granted = new LinkedHashMap<>();
        for (String name : names) {
            CredentialGrantStanding standing = standing(name);
            if (standing != CredentialGrantStanding.GRANTABLE) {
                throw new IllegalArgumentException(
                        "credential '" + name + "' cannot be granted to a program executor: " + standing);
            }
            values.find(name).ifPresent(value -> granted.put(name, value));
        }
        return Collections.unmodifiableMap(granted);
    }
}
