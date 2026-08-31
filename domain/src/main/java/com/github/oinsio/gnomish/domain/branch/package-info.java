/**
 * The task-branch contract: the closed set of branch {@linkplain
 * com.github.oinsio.gnomish.domain.branch.BranchShape shapes} a tip classifies to, the {@linkplain
 * com.github.oinsio.gnomish.domain.branch.BranchShapeClassifier classifier} that maps facts onto
 * them totally, and the {@linkplain com.github.oinsio.gnomish.domain.branch.ClaimEpoch epoch} that
 * fences a tenure's writes.
 *
 * <p>Media access lives above this package: adapters read a tip and assemble {@link
 * com.github.oinsio.gnomish.domain.branch.BranchTipFacts}, this package decides what the facts mean
 * (design D3 of harden-task-branch-contract).
 */
@NullMarked
package com.github.oinsio.gnomish.domain.branch;

import org.jspecify.annotations.NullMarked;
