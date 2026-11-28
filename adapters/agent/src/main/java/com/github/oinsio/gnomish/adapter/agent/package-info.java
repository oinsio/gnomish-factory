/**
 * Real {@code agent-cli} adapters (design D2 of add-stage-engine, D7 of
 * add-agent-executor): the agent CLI's output parser (now the Claude dialect's, in
 * {@code adapter.agent.claude}), the process
 * launcher that spawns the CLI binary, the {@code StageExecutor} adapter built on top of them, and
 * the judge's criteria preflight and prompt. The domain never spawns
 * processes or parses CLI output itself — this package is where the ports
 * meet the real {@code claude} CLI.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.agent;

import org.jspecify.annotations.NullMarked;
