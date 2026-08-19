package com.github.oinsio.gnomish.sandbox;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the {@link AdapterBinding} a stage runs under from operator
 * installation config (design D8, D13 of add-sandbox-core): the single seam that
 * applies the container-by-default rule and turns configured binding names into
 * typed bindings. Constructed once from {@link BindingProperties} and the
 * discovered {@link AdapterBindingRegistry}, it resolves and validates every
 * configured name up front, so an unknown binding name fails at startup with the
 * <em>discovered</em> options named (UX1) rather than mid-task.
 *
 * <p>The default binding is {@code container} whenever the operator configures no
 * default (D13): a stage with no explicit override binds the container adapter,
 * never the host — there is no silent fallback to weaker isolation. Host is
 * reachable only when the operator names it explicitly, per stage or as the
 * default.
 *
 * <p>The unset default is resolved <em>eagerly</em>, before the per-stage
 * overrides (D4 of open-adapter-binding-registry): in a distribution whose
 * container backend module is absent, the declared default is unsatisfiable, and
 * that is reported at startup even when every stage explicitly binds {@code host}
 * — a build that cannot honour its own default should say so, not run half of it.
 *
 * <p>Implements FR14 of add-sandbox-core; G2 of add-sandbox-core; FR4, FR5 of
 * open-adapter-binding-registry.
 */
public class BindingResolver {

    private final AdapterBinding defaultBinding;
    private final Map<String, AdapterBinding> stageBindings;

    /**
     * Resolves {@code config} into typed bindings against {@code registry},
     * validating every name (default and per-stage) eagerly.
     *
     * @param config the operator's binding configuration; never null
     * @param registry the discovered bindings; never null
     * @throws IllegalArgumentException if any configured binding name is not
     *     discovered, or the unset default's {@code container} binding is absent
     */
    public BindingResolver(BindingProperties config, AdapterBindingRegistry registry) {
        this.defaultBinding = resolveDefault(config.defaultBinding(), registry);
        this.stageBindings = resolveStageBindings(config.stages(), registry);
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
     * Resolves the unset default to the discovered {@code container} binding (D13);
     * a set value is resolved and validated like any other name. Kept explicit so
     * the container-default rule lives in exactly one place — and so its absence
     * gets its own refusal, naming both ways out rather than the generic
     * unknown-name message an operator never typed a name for (D4, M3).
     */
    private static AdapterBinding resolveDefault(@Nullable String configuredDefault, AdapterBindingRegistry registry) {
        if (configuredDefault != null) {
            return registry.require(configuredDefault);
        }
        AdapterBinding container = registry.find(BindingNames.CONTAINER);
        if (container == null) {
            throw new IllegalArgumentException("the default '" + BindingNames.CONTAINER
                    + "' adapter binding is not available; discovered bindings are " + registry.names()
                    + " — restore the container backend module on the classpath, or set factory.bindings.default="
                    + BindingNames.HOST + " if this trusted environment should run unsandboxed");
        }
        return container;
    }

    private static Map<String, AdapterBinding> resolveStageBindings(
            Map<String, String> configured, AdapterBindingRegistry registry) {
        Map<String, AdapterBinding> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            resolved.put(entry.getKey(), registry.require(entry.getValue()));
        }
        return Map.copyOf(resolved);
    }
}
