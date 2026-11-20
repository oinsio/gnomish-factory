/**
 * The agent round runner and its dialect seam (FR1, FR2, design D1, D8 of extract-agent-round-runner):
 * the one runner that executes an agent CLI round in any role, and the {@link
 * com.github.oinsio.gnomish.adapter.agent.runner.AgentDialect} contract that makes a vendor's CLI one
 * replaceable implementation behind it. Nothing in this package names a vendor: a dialect's own
 * event type stays a type parameter here, and every value a dialect hands the runner is one of this
 * package's vendor-neutral types or the executor contract's.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.agent.runner;

import org.jspecify.annotations.NullMarked;
