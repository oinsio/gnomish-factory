package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.status.StatusSnapshotHolder;

/**
 * The collaborators {@link ManualRunRunner} needs once {@link ManualRunAssembly#assemble} has built
 * them. {@code holder} is exposed so a caller (or a test, task 9.4) can observe the same {@link
 * StatusSnapshotHolder} the wired executor's progress listener enriches.
 */
record Run(RunnerOutcomeLoop loop, EnginePorts ports, StatusSnapshotHolder holder) {}
