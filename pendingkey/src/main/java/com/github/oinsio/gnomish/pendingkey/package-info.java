/**
 * The pending key's one owner: {@link com.github.oinsio.gnomish.pendingkey.PendingKey}, the opaque
 * key an executor returns with status {@code pending} and the factory polls with, and the judgement
 * a reader applies to a written string before it constructs one.
 *
 * <p><strong>Neutrality contract.</strong> This package imports the JDK and nothing else — no
 * other module of the factory, no Spring, no Jackson, no logging API. {@code :domain} (the engine's
 * pending result, the pending turn) and {@code :executor-contract} (the result's {@code pendingKey})
 * both name the type, so the rule has one implementation both reach (design D20 of
 * migrate-verify-to-executor-contract); the Gradle layering gate states the same rule as data, with
 * an allowlist that is empty.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR2, FR3 of migrate-verify-to-executor-contract.
 */
@NullMarked
package com.github.oinsio.gnomish.pendingkey;

import org.jspecify.annotations.NullMarked;
