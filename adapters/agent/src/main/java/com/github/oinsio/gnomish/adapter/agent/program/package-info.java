/**
 * The {@code program} executor (FR5, FR10 of define-executor-contract; design D3): the external
 * adapter of the executor contract, which runs a law-declared program through the task execution
 * environment — the request on its standard input as a file the launch owner hands it, its result in
 * factory-named files read through the one result reader, its stdout and stderr logs — and the
 * stage executor that runs a {@code program} stage's round through the mode's round source.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.agent.program;

import org.jspecify.annotations.NullMarked;
