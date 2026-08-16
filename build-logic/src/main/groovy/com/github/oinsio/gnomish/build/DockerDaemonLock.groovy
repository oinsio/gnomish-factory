package com.github.oinsio.gnomish.build

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Mutual exclusion over the one Docker daemon a build has (task 5.1 of split-into-modules).
 * Registered build-wide with {@code maxParallelUsages = 1}, so no two test tasks that materialize
 * real containers ever run at the same time; every other module's tests keep running in parallel
 * with whichever holds it.
 *
 * <p>The need appeared with the module split, not before it. While the whole tree was one Gradle
 * module every Docker-touching spec ran in a single test task and was serialized by construction.
 * Once the adapters moved out, {@code :adapters:test} (the container-environment contract suites)
 * and {@code :test} (the container-mode and Gitea E2E layers) became two tasks Gradle is free to
 * run concurrently — and concurrently they exhaust the daemon: the seed-clone helper container
 * fails with a non-zero exit after the clone itself has already reported success. Measured on this
 * tree: each task passes alone, both pass under {@code --max-workers=1}, and four container specs
 * fail when they overlap.
 *
 * <p>A lock rather than a {@code mustRunAfter}: the two tasks are genuinely unordered, they simply
 * must not overlap, and stating that directly keeps the constraint from reading as a hidden
 * dependency.
 */
abstract class DockerDaemonLock implements BuildService<BuildServiceParameters.None> {
}
