/**
 * The {@code command} built-in executor (FR4, FR5, FR8 of add-command-executor; design D1, D2): a
 * stage's declared shell command run as {@code sh -c} through the round's task execution environment
 * on the shared command-run core, reported through the executor contract — and the factory the shared
 * round wrapper ({@code ProgramStageExecutor}) runs it by, as the registry's built-in {@code command}.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.agent.command;

import org.jspecify.annotations.NullMarked;
