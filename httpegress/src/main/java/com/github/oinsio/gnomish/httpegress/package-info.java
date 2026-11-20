/**
 * The factory's one HTTP egress owner (FR15, design D16 of migrate-verify-to-executor-contract):
 * the allowlist of hosts an in-process HTTP call may reach, the guarded exchange that judges every
 * hop against it before the hop is sent, and the one JDK {@code HttpClient} the factory's production
 * code constructs. Every in-process HTTP call of a built-in verdict executor passes it.
 *
 * <p><strong>Neutrality contract.</strong> This package imports the JDK and nothing else — no
 * other module of the factory, no Spring, no Jackson, no logging API. Its emptiness is a security
 * property: a guard that can import nothing cannot be handed a secrets provider, a tracker or a
 * configuration format, so the only things a caller can vary are the allowlist entries and the
 * request. The Gradle layering gate states the same rule as data, with an allowlist that is empty,
 * and {@code HttpClientConstructionBoundarySpec} in {@code :bootstrap} holds the construction of a
 * JDK client to this package.
 *
 * <p><strong>What stays outside.</strong> Composing a request from a manifest, resolving its
 * credential, judging the verdict and logging a refusal are the callers' — the {@code http}
 * built-in in {@code :adapters} is the first.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 *
 * <p>Implements NFR-S2 of add-plugin-architecture; FR15 of migrate-verify-to-executor-contract.
 */
@NullMarked
package com.github.oinsio.gnomish.httpegress;

import org.jspecify.annotations.NullMarked;
