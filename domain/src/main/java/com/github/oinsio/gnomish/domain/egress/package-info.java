/**
 * The values of HTTP egress admission (FR15, FR17, design D16 of
 * migrate-verify-to-executor-contract): the medium a URL comes from, the endpoint read with its
 * medium, the endpoint an adapter declares, the endpoint after admission, and the refusal of the
 * egress guard. Pure records with no behaviour beyond their invariants: the judgement itself is
 * {@code EgressAdmission} in {@code :httpegress}, which takes this package as its one internal
 * edge, so every module that names these values already reaches them.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.domain.egress;

import org.jspecify.annotations.NullMarked;
