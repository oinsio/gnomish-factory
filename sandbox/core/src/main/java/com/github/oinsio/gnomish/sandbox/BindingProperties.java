package com.github.oinsio.gnomish.sandbox;

import com.github.oinsio.gnomish.operatorconfig.ConfigLevel;
import com.github.oinsio.gnomish.operatorconfig.Level;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/**
 * Immutable typed configuration of the per-stage adapter bindings, bound from the
 * {@code factory.bindings.*} external properties via constructor binding,
 * mirroring {@code FactoryProperties} (design D8, D13). Kept independent of
 * {@link SandboxProperties} because a binding selects <em>which</em> adapter runs
 * a stage (host or container), while {@code factory.sandbox.*} configures the
 * container adapter once selected.
 *
 * <p>Binding names are carried as raw strings here, not the {@code AdapterBinding}
 * enum: this root configuration package stays decoupled from the adapter layer,
 * and the string→binding resolution — including the fail-closed error naming the
 * valid options for an unknown name — lives in {@code BindingResolver} (task
 * 3.2), the single seam that also applies the container-by-default rule (D13).
 *
 * <p>Both components are {@link Level#SANDBOX_BOUNDARY} keys (design D5 of add-project-registry):
 * a binding decides whether a stage runs in a box at all, and the stage names exist only in one
 * project's pipeline, so they are read from the project's own file alone.
 *
 * <p>Implements FR14 of add-sandbox-core; FR6, NFR-S1 of add-project-registry.
 *
 * @param defaultBinding the binding applied to every stage without an explicit
 *     override ({@code factory.bindings.default}); {@code null} when unset —
 *     resolved to the container default (D13) by {@code BindingResolver}, so no
 *     silent host fallback ever exists
 * @param stages the per-stage binding overrides, stage name → binding name
 *     ({@code factory.bindings.stages.*}); defaults to an empty map when unset
 */
@ConfigurationProperties("factory.bindings")
public record BindingProperties(
        @ConfigLevel(Level.SANDBOX_BOUNDARY) @Name("default") @Nullable
        String defaultBinding,

        @ConfigLevel(Level.SANDBOX_BOUNDARY) Map<String, String> stages) {

    // defaultBinding stays @Nullable so BindingResolver owns the container-default rule (D13) in one
    // place; stages is defensively defaulted because Spring's reflective binding can pass null for an
    // unset property despite this package's @NullMarked contract. The constructor is compact so the
    // component's @Name("default") reaches the canonical constructor's parameter, which is where
    // constructor binding reads it: an explicit constructor redeclares the parameters without the
    // annotation, and the documented `factory.bindings.default` key bound nothing (FR6 of
    // fix-operator-blockers, design D4). `default` is a Java keyword, so no component could carry it.
    public BindingProperties {
        stages = stages == null ? Map.of() : Map.copyOf(stages);
    }
}
