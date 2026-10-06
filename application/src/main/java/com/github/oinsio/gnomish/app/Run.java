package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.EnginePorts;

/**
 * The collaborators one invocation needs once {@link RunAssembly#assemble} has built them.
 *
 * <p>Public because it is the {@link RunAssembly} port's return type (task 4.4 of
 * split-into-modules) — every component of it already was.
 */
public record Run(RunnerOutcomeLoop loop, EnginePorts ports) {}
