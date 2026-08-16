package com.github.oinsio.gnomish.sandbox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The layered positive child-environment allowlist every {@code exec()} child is
 * composed from (design D6, FR9): the adapter's base set (names resolved from
 * the factory environment), plus the operator-configured passthrough names
 * ({@code factory.sandbox.env-passthrough} — exact names only, values read live
 * from the factory environment at exec time), plus the factory-set protocol
 * variables of the call. Nothing is inherited implicitly; one formula serves
 * both adapters — the host adapter passes its fixed documented base, the
 * container adapter an empty base (the image's own {@code ENV} supplies the
 * runtime environment).
 *
 * <p>A passthrough name the active tracker adapter declares as a credential is
 * refused at construction — a startup configuration error naming the variable,
 * the validation-time twin of the replaced scrub-last guarantee (D6). As defense
 * in depth the composed map also never contains a declared credential name,
 * whatever layer would have carried it. The applied names (never values) are
 * logged at debug per composition, so a missing-variable diagnosis is one log
 * read (UX6).
 *
 * <p>Implements FR9, NFR-S1 of add-sandbox-core.
 */
public final class ChildEnvAllowlist {

    private static final Logger log = LoggerFactory.getLogger(ChildEnvAllowlist.class);

    private final List<String> passthroughNames;
    private final Set<String> credentialNames;
    private final Supplier<Map<String, String>> factoryEnvironment;

    private ChildEnvAllowlist(
            List<String> passthroughNames,
            Collection<String> credentialNames,
            Supplier<Map<String, String>> factoryEnvironment) {
        this.passthroughNames = List.copyOf(passthroughNames);
        this.credentialNames = Set.copyOf(credentialNames);
        this.factoryEnvironment = factoryEnvironment;
    }

    /**
     * The production allowlist over the real factory process environment.
     *
     * @param passthroughNames the operator's {@code factory.sandbox.env-passthrough}
     *     names; never null, may be empty
     * @param credentialNames the active tracker adapter's declared credential
     *     env-var names; never null, empty when no tracker is involved
     * @return the validated allowlist; never null
     * @throws IllegalArgumentException if a passthrough name is a declared
     *     credential — a startup configuration error naming the variable (FR9)
     */
    public static ChildEnvAllowlist of(List<String> passthroughNames, Collection<String> credentialNames) {
        return validated(new ChildEnvAllowlist(passthroughNames, credentialNames, System::getenv));
    }

    /** The empty allowlist: no passthrough, no declared credentials (plain {@code gnomish run}). */
    public static ChildEnvAllowlist none() {
        return of(List.of(), List.of());
    }

    /**
     * Testing seam: the same allowlist over a caller-supplied factory-environment
     * source, so specs can plant and change variables without touching the JVM's
     * real environment.
     */
    static ChildEnvAllowlist over(
            List<String> passthroughNames,
            Collection<String> credentialNames,
            Supplier<Map<String, String>> factoryEnvironment) {
        return validated(new ChildEnvAllowlist(passthroughNames, credentialNames, factoryEnvironment));
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
     * names, each included only when present in the factory environment (values
     * read live at this call), then {@code factorySet} — later layers win on a
     * name collision. Declared credential names never appear in the result.
     *
     * @param baseNames the adapter's base set — the host adapter's fixed
     *     documented minimum, or empty for the container adapter; never null
     * @param factorySet the factory-set protocol variables of this exec; never null
     * @return the composed child environment; never null
     */
    public Map<String, String> compose(Collection<String> baseNames, Map<String, String> factorySet) {
        Map<String, String> factoryEnv = factoryEnvironment.get();
        Map<String, String> composed = new LinkedHashMap<>();
        putPresent(composed, baseNames, factoryEnv);
        putPresent(composed, passthroughNames, factoryEnv);
        composed.putAll(factorySet);
        composed.keySet().removeAll(credentialNames);
        log.debug("child environment allowlist applied (names only): {}", composed.keySet());
        return composed;
    }

    private static void putPresent(
            Map<String, String> composed, Collection<String> names, Map<String, String> factoryEnv) {
        for (String name : names) {
            String value = factoryEnv.get(name);
            if (value != null) {
                composed.put(name, value);
            }
        }
    }
}
