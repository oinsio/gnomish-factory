package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.status.StatusSnapshotHolder;

/**
 * The collaborators one invocation needs once {@link RunAssembly#assemble} has built them. {@code
 * holder} is exposed so a caller (or a test, task 9.4) can observe the same {@link
 * StatusSnapshotHolder} the wired executor's progress listener enriches.
 *
 * <p>Public because it is the {@link RunAssembly} port's return type (task 4.4 of
 * split-into-modules) — every component of it already was.
 */
public record Run(RunnerOutcomeLoop loop, EnginePorts ports, StatusSnapshotHolder holder) {}
