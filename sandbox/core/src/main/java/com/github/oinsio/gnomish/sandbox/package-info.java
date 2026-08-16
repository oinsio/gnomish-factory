/**
 * The sandbox port layer (FR8, design D7, D11 of split-into-modules): the
 * {@code TaskExecutionEnvironment} port every execution-environment backend
 * implements, the capability-passport model the factory reconciles a stage's
 * declared needs against, the operator's adapter binding, and the typed sandbox
 * configuration. Backend mechanics — today the subprocess docker-CLI adapter,
 * later backends bringing their own SDKs — live in the backend modules, never
 * here.
 *
 * <p>Port and value model: {@link
 * com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment} — the port
 * (materialize / exec / putFile / readFile / harvest / dispose, plus the scratch
 * root and the capability passport); {@link
 * com.github.oinsio.gnomish.sandbox.ExecCommand} and {@link
 * com.github.oinsio.gnomish.sandbox.ExecHandle} — the exec request and the live
 * process handle (streamed output, exit control); {@link
 * com.github.oinsio.gnomish.sandbox.ProcessStartException} — the launch-failure
 * signal.
 *
 * <p>Capability negotiation (task group 3 of add-sandbox-core, FR12–FR14):
 * {@link com.github.oinsio.gnomish.sandbox.CapabilityPassport} and {@link
 * com.github.oinsio.gnomish.sandbox.IsolationLevel} — the machine-readable
 * passport a backend declares; {@link
 * com.github.oinsio.gnomish.sandbox.SandboxNeed} and {@link
 * com.github.oinsio.gnomish.sandbox.SandboxReconciler} — reconcile a stage's
 * declared needs against the bound passport, fail-closed on any unmet one;
 * {@link com.github.oinsio.gnomish.sandbox.AdapterBinding} — the operator's
 * host/container choice and its fixed passport; {@link
 * com.github.oinsio.gnomish.sandbox.BindingResolver} — resolves each stage to a
 * binding from {@code factory.bindings.*}, container by default with no silent
 * host fallback.
 *
 * <p>Typed configuration (design D1's layer-home rule): {@link
 * com.github.oinsio.gnomish.sandbox.SandboxProperties} and its nested {@link
 * com.github.oinsio.gnomish.sandbox.ResourceLimits} carry {@code
 * factory.sandbox.*}; {@link com.github.oinsio.gnomish.sandbox.BindingProperties}
 * carries {@code factory.bindings.*}. They travel with their consumers into this
 * module and are picked up by the bootstrap {@code @ConfigurationPropertiesScan}.
 *
 * <p>Implements FR1, FR2, FR3, FR4, FR9, FR12, FR13, FR14, FR24, NFR-R1, NFR-S3
 * of add-sandbox-core; FR8 of split-into-modules.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.sandbox;

import org.jspecify.annotations.NullMarked;
