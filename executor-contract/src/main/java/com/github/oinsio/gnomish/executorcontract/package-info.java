/**
 * The executor contract's Java mirror: the request, result and describe documents every executor
 * of the factory speaks, and the value types and wire enums they carry. Each type mirrors one
 * object of the JSON Schema files under {@code schemas/} on this module's classpath; the schema
 * files are the contract's single source of truth and {@code WireSchemaRoundTripSpec} holds the two
 * equal (design D10 of define-executor-contract, "Java DTOs and JSON schema are one").
 *
 * <p><strong>Wire tokens.</strong> Every wire enum spells its tokens explicitly through {@code
 * wire()} and reads them back through {@code fromWire(String)}; a Java constant name is never a
 * wire token. Each enum states its unknown-token arm in its javadoc, the same arm its schema states.
 *
 * <p><strong>Neutrality contract.</strong> This package imports the JDK, Jackson and the JDK-only
 * {@code untrustedtext} leaf (the carrier the result-file reader returns text in) and nothing else:
 * the engine, the adapters and the conformance kit all reach it, and an edge added here would land
 * in each of them.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR1, NFR-R3 of define-executor-contract.
 */
@NullMarked
package com.github.oinsio.gnomish.executorcontract;

import org.jspecify.annotations.NullMarked;
