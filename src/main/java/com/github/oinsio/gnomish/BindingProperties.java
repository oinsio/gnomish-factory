package com.github.oinsio.gnomish;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/**
 * Immutable typed configuration of the per-stage adapter bindings, bound from the
 * {@code factory.bindings.*} external properties via constructor binding,
 * mirroring {@link FactoryProperties} (design D8, D13). Kept independent of
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
 * <p>Implements FR14 of add-sandbox-core.
 *
 * @param defaultBinding the binding applied to every stage without an explicit
 *     override ({@code factory.bindings.default}); {@code null} when unset —
 *     resolved to the container default (D13) by {@code BindingResolver}, so no
 *     silent host fallback ever exists
 * @param stages the per-stage binding overrides, stage name → binding name
 *     ({@code factory.bindings.stages.*}); defaults to an empty map when unset
 */
@ConfigurationProperties("factory.bindings")
public record BindingProperties(@Name("default") @Nullable String defaultBinding, Map<String, String> stages) {

    // defaultBinding stays @Nullable so BindingResolver owns the container-default rule (D13) in one
    // place; stages is defensively defaulted because Spring's reflective binding can pass null for an
    // unset property despite this package's @NullMarked contract. @Name binds the documented
    // `factory.bindings.default` key — `default` is a Java keyword, so no component could carry it.
    public BindingProperties(@Nullable String defaultBinding, @Nullable Map<String, String> stages) {
        this.defaultBinding = defaultBinding;
        this.stages = stages == null ? Map.of() : Map.copyOf(stages);
    }
}
