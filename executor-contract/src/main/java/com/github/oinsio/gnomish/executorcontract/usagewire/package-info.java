/**
 * The one usage wire vocabulary (design D10, D14 of define-executor-contract): the DTOs and tokens
 * every recorded usage document spells usage through — {@code state.json}, the status report and
 * the usage report alike, so the three documents cannot word a participant entry differently.
 * The three per-document DTO trees this package replaced were dissolved into it under the rule of
 * three rather than declared as a pair.
 *
 * <p>A participant entry reuses the contract's own shapes where they are the same wire: the role
 * tokens of {@link com.github.oinsio.gnomish.executorcontract.Role}, the per-model {@link
 * com.github.oinsio.gnomish.executorcontract.ModelUsage} and the per-tool {@link
 * com.github.oinsio.gnomish.executorcontract.ToolUsage}. The provenance is spelled in the record
 * documents' lowerCamel convention ({@link RecordedProvenance}), not in the result file's upper
 * case. The domain types are mapped onto these DTOs by one mapper in {@code :application}: this
 * module reaches no factory layer.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * carry an explicit {@code @Nullable}.
 *
 * <p>Implements FR15, FR16, NFR-R3 of define-executor-contract.
 */
@NullMarked
package com.github.oinsio.gnomish.executorcontract.usagewire;

import org.jspecify.annotations.NullMarked;
