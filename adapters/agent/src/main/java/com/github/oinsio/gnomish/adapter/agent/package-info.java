/**
 * Real {@code agent-cli} adapters (design D2 of add-stage-engine, D7 of
 * add-agent-executor): the stream-json wire protocol parser, the process
 * launcher that spawns the CLI binary, and the {@code StageExecutor}/{@code
 * JudgeVoter} adapters built on top of them. The domain never spawns
 * processes or parses CLI output itself — this package is where the ports
 * meet the real {@code claude} CLI.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.agent;

import org.jspecify.annotations.NullMarked;
