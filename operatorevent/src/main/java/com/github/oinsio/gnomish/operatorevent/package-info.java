/**
 * The factory's operator-event catalog: {@link
 * com.github.oinsio.gnomish.operatorevent.OperatorEvent}, one constant per production WARN/ERROR
 * call site, each owning a stable {@code [GFnnn]} code the site renders as its message head. The
 * code — not the wording — is what an operator's alert, an operator's grep and a spec's assertion
 * key on, so prose may be rewritten freely without breaking any of them.
 *
 * <p><strong>Neutrality contract.</strong> This package imports the JDK and nothing else — no other
 * module of the factory, no Spring, no Jackson, no logging API. The emptiness is load-bearing
 * twice over: {@code :domain} reaches this leaf so its emitters render the head from the constant
 * rather than repeating it as a literal, and the published {@code :gnomish-plugin-api} reaches it
 * through {@code :domain}, so an edge added here lands in the domain and in the contract's
 * published POM alike. The Gradle layering gate states the same rule as data, with an allowlist
 * that is empty.
 *
 * <p><strong>What stays outside.</strong> This is an identity, not policy: the catalog decides
 * neither which level a line takes nor how its untrusted text is neutralized — those belong to the
 * emitters, to {@code logtext.LogText} and to the sink layer, under the policy written down in
 * {@code docs/adr/0004-logging-policy.md} and {@code .claude/rules/logging.md}. The correspondence
 * between constants and call sites — every site coded, every code used once, no literal head — is
 * a scan of the source tree and lives in the log-contract gate in {@code :bootstrap}.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR4 of split-logtext-leaves.
 */
@NullMarked
package com.github.oinsio.gnomish.operatorevent;

import org.jspecify.annotations.NullMarked;
