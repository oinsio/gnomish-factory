package com.github.oinsio.gnomish.sandbox;

/**
 * Which execution-environment adapter the operator binds a stage to (design D8,
 * D13 of add-sandbox-core; D3 of open-adapter-binding-registry): a config name
 * paired with the fixed {@link CapabilityPassport} the factory reconciles a
 * stage's declared needs against before the stage runs. A binding is an
 * operator-only choice — the repo may only tighten needs, never name a binding
 * (FR14) — configured by name in {@code factory.bindings.*} and resolved through
 * {@link AdapterBindingRegistry} by {@link BindingResolver}.
 *
 * <p>This was a sealed enum whose constants lived in core; it is now a plain
 * value, minted by the registry from a discovered {@link SandboxBindingProvider}
 * and the passport the core trust table ratifies for it (FR1). A new backend
 * therefore contributes a binding from its own module, with no core enum edit.
 * The passport is still available with no live adapter instance (FR2), so
 * reconciliation and segment planning stay daemon-free.
 *
 * <p>Bindings are compared <em>by value</em>, not by reference: the registry
 * guarantees one instance per config name, but every caller — including
 * {@link SegmentPlanner}'s segment-boundary test, once a reference {@code !=} —
 * compares {@link #configName()}, so a binding minted anywhere equal to another
 * behaves identically (D3).
 *
 * <p>Implements FR14 of add-sandbox-core; FR1, FR2, FR9 of
 * open-adapter-binding-registry.
 *
 * @param configName the lower-case name this binding is spelled with in {@code
 *     factory.bindings.*}; also its trust-table id
 * @param passport the fixed capability passport reconciliation checks against —
 *     the value the core trust table holds for this id, never the provider's own
 *     unverified declaration
 */
public record AdapterBinding(String configName, CapabilityPassport passport) {}
