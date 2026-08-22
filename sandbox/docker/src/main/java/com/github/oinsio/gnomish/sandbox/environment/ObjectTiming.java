package com.github.oinsio.gnomish.sandbox.environment;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The runtime metadata the sweep-lifecycle evaluator ages an object by — always {@code
 * createdAt}; a container additionally carries {@code running}, {@code startedAt} (manual
 * running-stop age), and {@code finishedAt} once stopped (aged-reap age). Never file mtimes
 * inside a volume (`sandbox-lifecycle`, "Aged reaper for kept environments").
 */
record ObjectTiming(
        boolean running,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant finishedAt) {}
