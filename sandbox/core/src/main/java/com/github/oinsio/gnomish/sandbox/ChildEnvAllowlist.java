package com.github.oinsio.gnomish.sandbox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The layered positive child-environment allowlist every {@code exec()} child is
 * composed from (design D6, FR9): the adapter's base set, plus the
 * operator-configured passthrough names ({@code factory.sandbox.env-passthrough}
 * — exact names only), plus the factory-set protocol variables of the call.
 * Nothing is inherited implicitly; one formula serves both adapters — the host
 * adapter passes its fixed documented base, the container adapter an empty base
 * (the image's own {@code ENV} supplies the runtime environment).
 *
 * <p>Every value the allowlist admits is resolved live, by name, through its
 * {@link ChildEnvValues} — in production the factory's secrets port, so no
 * variable reaches a child around it, and the env/file adapter stays the only
 * reader of the process environment (NFR-S3, finding F107 of
 * tighten-spec-contract). The allowlist exposes no accessor for that source: a
 * holder can compose a child environment or resolve the factory-set variables an
 * agent round names ({@link #resolve}), never enumerate or read around the policy.
 *
 * <p>A passthrough name the active tracker adapter declares as a credential is
 * refused at construction — a startup configuration error naming the variable,
 * the validation-time twin of the replaced scrub-last guarantee (D6). As defense
 * in depth the composed map also never contains a declared credential name,
 * whatever layer would have carried it. The applied names (never values) are
 * logged at debug per composition, so a missing-variable diagnosis is one log
 * read (UX6).
 *
 * <p>Implements FR9, NFR-S1 of add-sandbox-core; NFR-S3 of tighten-spec-contract.
 */
public final class ChildEnvAllowlist {

    private static final Logger log = LoggerFactory.getLogger(ChildEnvAllowlist.class);

    private final List<String> passthroughNames;
    private final Set<String> credentialNames;
    private final ChildEnvValues values;

    private ChildEnvAllowlist(List<String> passthroughNames, Set<String> credentialNames, ChildEnvValues values) {
        this.passthroughNames = List.copyOf(passthroughNames);
        this.credentialNames = Set.copyOf(credentialNames);
        this.values = values;
    }

    /**
     * The allowlist over {@code values}.
     *
     * @param passthroughNames the operator's {@code factory.sandbox.env-passthrough}
     *     names; never null, may be empty
     * @param credentialNames the active tracker adapter's declared credential
     *     env-var names; never null, empty when no tracker is involved
     * @param values where each admitted name's value is resolved — the composition
     *     root's {@code SecretsProvider::find}; never null
     * @return the validated allowlist; never null
     * @throws IllegalArgumentException if a passthrough name is a declared
     *     credential — a startup configuration error naming the variable (FR9)
     */
    public static ChildEnvAllowlist of(
            List<String> passthroughNames, Collection<String> credentialNames, ChildEnvValues values) {
        return validated(new ChildEnvAllowlist(passthroughNames, Set.copyOf(credentialNames), values));
    }

    /**
     * The empty allowlist: no passthrough, no declared credentials, and no value
     * resolves ({@link ChildEnvValues#NONE}), so a composition carries the
     * factory-set layer alone.
     */
    public static ChildEnvAllowlist none() {
        return of(List.of(), List.of(), ChildEnvValues.NONE);
    }

    /**
     * The same allowlist with {@code moreCredentials} declared besides its own —
     * the per-run widening of an installation-wide allowlist by the run's tracker
     * and pipeline credentials, validated again over the union.
     *
     * @param moreCredentials further declared credential names; never null
     * @return the widened, validated allowlist; never null
     * @throws IllegalArgumentException if a passthrough name is now a declared credential
     */
    public ChildEnvAllowlist declaring(Collection<String> moreCredentials) {
        Set<String> union = new LinkedHashSet<>(credentialNames);
        union.addAll(moreCredentials);
        return validated(new ChildEnvAllowlist(passthroughNames, union, values));
    }

    private static ChildEnvAllowlist validated(ChildEnvAllowlist allowlist) {
        List<String> refused = new ArrayList<>();
        for (String name : allowlist.passthroughNames) {
            if (allowlist.credentialNames.contains(name)) {
                refused.add(name);
            }
        }
        if (!refused.isEmpty()) {
            throw new IllegalArgumentException(
                    "factory.sandbox.env-passthrough must not name declared credential variables: " + refused
                            + " (FR9 of add-sandbox-core: a credential can never be allowlisted into a child"
                            + " environment)");
        }
        return allowlist;
    }

    /**
     * Composes one child environment: {@code baseNames} then the passthrough
     * names, each included only when its value resolves (read live at this call), then {@code factorySet} — later layers win on a
     * name collision. Declared credential names never appear in the result.
     *
     * @param baseNames the adapter's base set — the host adapter's fixed
     *     documented minimum, or empty for the container adapter; never null
     * @param factorySet the factory-set protocol variables of this exec; never null
     * @return the composed child environment; never null
     */
    public Map<String, String> compose(Collection<String> baseNames, Map<String, String> factorySet) {
        Map<String, String> composed = new LinkedHashMap<>();
        putPresent(composed, baseNames);
        putPresent(composed, passthroughNames);
        composed.putAll(factorySet);
        composed.keySet().removeAll(credentialNames);
        log.debug("child environment allowlist applied (names only): {}", composed.keySet());
        return composed;
    }

    /**
     * The factory-set variables {@code names} asks for, each resolved live through
     * this allowlist's values and included only when present, in {@code names}'
     * order; a declared credential name never appears — the same exclusion
     * {@link #compose} applies to every layer. The agent adapters' AI seam reads
     * the agent CLI's own credentials here, so they come through the same source
     * as every other admitted value (NFR-S3 of tighten-spec-contract).
     *
     * @param names the variable names to resolve; never null
     * @return the present ones with their values; never null
     */
    public Map<String, String> resolve(Collection<String> names) {
        Map<String, String> resolved = new LinkedHashMap<>();
        putPresent(resolved, names);
        resolved.keySet().removeAll(credentialNames);
        return resolved;
    }

    private void putPresent(Map<String, String> composed, Collection<String> names) {
        for (String name : names) {
            values.find(name).ifPresent(value -> composed.put(name, value));
        }
    }
}
