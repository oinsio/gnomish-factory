package com.github.oinsio.gnomish.sandbox;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the {@link AdapterBinding} a stage runs under from operator
 * installation config (design D8, D13): the single seam that applies the
 * container-by-default rule and turns configured binding names into typed
 * bindings. Constructed once from {@link BindingProperties}, it parses and
 * validates every configured name up front, so an unknown binding name fails at
 * startup with the valid options named (UX2) rather than mid-task.
 *
 * <p>The default binding is {@code container} whenever the operator configures no
 * default (D13): a stage with no explicit override binds the container adapter,
 * never the host — there is no silent fallback to weaker isolation. Host is
 * reachable only when the operator names it explicitly, per stage or as the
 * default.
 *
 * <p>Implements FR14 of add-sandbox-core; G2 of add-sandbox-core.
 */
public class BindingResolver {

    private final AdapterBinding defaultBinding;
    private final Map<String, AdapterBinding> stageBindings;

    /**
     * Parses {@code config} into typed bindings, validating every name (default and
     * per-stage) eagerly.
     *
     * @param config the operator's binding configuration; never null
     * @throws IllegalArgumentException if any configured binding name is unknown
     */
    public BindingResolver(BindingProperties config) {
        this.defaultBinding = resolveDefault(config.defaultBinding());
        this.stageBindings = parseStageBindings(config.stages());
    }

    /**
     * The binding stage {@code stageName} runs under: its explicit override when
     * configured, else the default binding (container unless the operator set
     * another). Never null and never a silent weakening — an unconfigured stage
     * gets the container default, not the host (D13).
     *
     * @param stageName the stage to resolve a binding for; never null
     * @return the resolved binding; never null
     */
    public AdapterBinding resolve(String stageName) {
        return stageBindings.getOrDefault(stageName, defaultBinding);
    }

    /**
     * Resolves the unset default to {@link AdapterBinding#CONTAINER} (D13); a set
     * value is parsed and validated. Kept explicit so the container-default rule
     * lives in exactly one place.
     */
    private static AdapterBinding resolveDefault(@Nullable String configuredDefault) {
        return configuredDefault == null ? AdapterBinding.CONTAINER : AdapterBinding.parse(configuredDefault);
    }

    private static Map<String, AdapterBinding> parseStageBindings(Map<String, String> configured) {
        Map<String, AdapterBinding> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            resolved.put(entry.getKey(), AdapterBinding.parse(entry.getValue()));
        }
        return Map.copyOf(resolved);
    }
}
