package com.github.oinsio.gnomish.sandbox;

/**
 * The first-party contribution point for an adapter binding (design D1, D5 of
 * open-adapter-binding-registry): a sandbox backend module ships one provider
 * per binding it offers, plus a {@code META-INF/services} entry, and its binding
 * joins the {@link AdapterBindingRegistry} with no edit to any core source file
 * beyond its one-line trust-table registration (FR1, G1).
 *
 * <p>A provider is a <em>descriptor</em>, never a factory: it names the binding
 * and declares the {@link CapabilityPassport} it claims, and nothing else. Both
 * methods must be cheap and SDK-free — no docker client, no daemon, no live
 * adapter — so enumerating the bindings and reconciling a stage's declared needs
 * against them stay adapter-instance-free, exactly as the sealed {@code
 * AdapterBinding} enum guaranteed before this registry replaced it (FR2). The
 * environment factory is deliberately absent: the live execution path selects
 * whole runner families in the composition root today, so a {@code create(...)}
 * method would be declared and wired to nothing; it lands with its first
 * consumer, the run-path generalization in {@code add-sandbox-colima-vm} (D5).
 *
 * <p>Discovery is <strong>first-party only</strong>. The declared passport is a
 * cross-checked proposal, never the authority: {@link AdapterBindingRegistry}
 * ratifies every provider against the core-owned trust table and refuses an
 * unknown id or a differing passport fail-fast (D2, FR7, FR10, NFR-S1). This
 * type is therefore public — the composition root loads it — but deliberately
 * outside {@code gnomish-plugin-api}: the sandbox is a trust boundary and stays
 * off the third-party plugin surface.
 *
 * <p>Implementations must have a public no-arg constructor ({@code ServiceLoader}
 * requirement) and must be stateless — the registry is built once at startup.
 *
 * <p>Implements FR1, FR2 of open-adapter-binding-registry.
 */
public interface SandboxBindingProvider {

    /**
     * The lower-case name this binding is spelled with in {@code factory.bindings.*}
     * configuration, and the id the core trust table registers it under — one
     * identifier, no separate id space (D2).
     *
     * @return the config spelling; never null, never blank
     */
    String configName();

    /**
     * The capability passport this binding claims, fixed for the life of the
     * process and computed without touching a backend SDK or daemon (FR2).
     *
     * @return the declared passport; never null
     */
    CapabilityPassport passport();
}
